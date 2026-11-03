"""
Exporta Depth Anything 3 SMALL (Apache-2.0) a ONNX para la app (ONNX Runtime en el móvil).

    pip install torch torchvision onnx onnxruntime
    pip install "git+https://github.com/ByteDance-Seed/Depth-Anything-3"
    python tools/export_da3_onnx.py --out modelos/da3_small.onnx

Licencia: sólo DA3-SMALL / DA3-BASE (Apache-2.0). Large/Giant/Nested son CC BY-NC (no comercial).

El repositorio oficial no trae exportación ONNX: su `forward` usa autocast y CUDA. Aquí se llama a
la red (`model.model`) directamente en float32 con entrada fija [1, 1, 3, 378, 504] (una vista,
4:3 apaisada como el sensor, múltiplos de 14) normalizada ImageNet — lo mismo que hace la app — y se
COMPRUEBA contra la inferencia oficial `model.inference()`: si no coinciden, falla.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import torch

H, W = 378, 504
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


def _cartesian_prod(*ts):
    """torch.cartesian_prod con operaciones que ONNX sí tiene (meshgrid + stack): mismo resultado."""
    if len(ts) == 1:
        return ts[0]
    grids = torch.meshgrid(*ts, indexing="ij")
    return torch.stack([g.reshape(-1) for g in grids], dim=-1)


def patch_for_onnx():
    """La red usa aten::cartesian_prod (codificación de posiciones), que ONNX no soporta."""
    import torch.functional
    original = torch.cartesian_prod
    # Comprobación: idéntico al original antes de sustituirlo.
    a, b = torch.arange(3.0), torch.arange(4.0)
    assert torch.equal(_cartesian_prod(a, b), original(a, b))
    torch.cartesian_prod = _cartesian_prod
    torch.functional.cartesian_prod = _cartesian_prod


def test_image():
    """Imagen de prueba con textura y profundidad variada (no hace falta ninguna foto)."""
    rng = np.random.default_rng(0)
    yy, xx = np.mgrid[0:H, 0:W]
    img = np.zeros((H, W, 3), np.float32)
    img[..., 0] = 0.5 + 0.4 * np.sin(xx / 17.0) * np.cos(yy / 23.0)
    img[..., 1] = 0.4 + 0.3 * (yy / H)
    img[..., 2] = 0.3 + 0.3 * ((xx // 40 + yy // 40) % 2)
    for _ in range(60):  # "objetos" con bordes
        x, y, r = rng.integers(0, W), rng.integers(0, H), rng.integers(10, 50)
        img[(xx - x) ** 2 + (yy - y) ** 2 < r * r] = rng.random(3)
    return (np.clip(img, 0, 1) * 255).astype(np.uint8)


def find_depth(out):
    """Saca (profundidad, confianza) de la salida de la red, sea dict o un objeto con atributos."""
    items = out.items() if hasattr(out, "items") else vars(out).items()
    tensors = {k: v for k, v in items if torch.is_tensor(v)}
    print("[export] salidas de la red:", {k: tuple(v.shape) for k, v in tensors.items()})
    depth = next((v for k, v in tensors.items() if k == "depth"), None)
    if depth is None:
        depth = next(v for k, v in tensors.items() if "depth" in k and "conf" not in k)
    conf = next((v for k, v in tensors.items() if "conf" in k), None)
    return depth, conf


class Wrapper(torch.nn.Module):
    """Imagen normalizada [1,1,3,H,W] -> profundidad [1,H,W] (y confianza si la hay)."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, image):
        out = self.net(image, None, None, [], False, False, "saddle_balanced")
        depth, conf = find_depth(out)
        depth = depth.reshape(1, H, W)
        if conf is not None:
            return depth, conf.reshape(1, H, W)
        return (depth,)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="depth-anything/DA3-SMALL")
    ap.add_argument("--out", type=Path, default=Path("modelos/da3_small.onnx"))
    a = ap.parse_args()
    if not any(s in a.model.upper() for s in ("DA3-SMALL", "DA3-BASE")):
        raise SystemExit("Sólo DA3-SMALL o DA3-BASE (Apache-2.0); el resto es no comercial.")
    from depth_anything_3.api import DepthAnything3

    api = DepthAnything3.from_pretrained(a.model).to("cpu").float().eval()
    net = api.model.float().eval()
    img = test_image()
    x = torch.from_numpy(((img.astype(np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1))[None, None].contiguous()

    patch_for_onnx()
    wrap = Wrapper(net).eval()
    with torch.no_grad():
        ref = wrap(x)
    names = ["depth", "conf"][:len(ref)]
    a.out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(wrap, (x,), str(a.out), input_names=["image"], output_names=names, opset_version=17,
                      do_constant_folding=True, dynamo=False)
    print(f"[export] {a.out}: {a.out.stat().st_size / 1e6:.1f} MB")

    # 1) ONNX Runtime == PyTorch (la exportación no cambió nada).
    import onnxruntime as ort
    sess = ort.InferenceSession(str(a.out), providers=["CPUExecutionProvider"])
    io = {"inputs": [(i.name, i.shape) for i in sess.get_inputs()], "outputs": [(o.name, o.shape) for o in sess.get_outputs()]}
    print("[export] tensores ONNX:", io)
    got = sess.run(None, {"image": x.numpy()})
    d_ref = ref[0].numpy(); d_onnx = got[0]
    rel = float(np.max(np.abs(d_onnx - d_ref)) / (np.max(np.abs(d_ref)) + 1e-9))
    print(f"[export] ONNX vs PyTorch: diferencia máx relativa {rel:.2e}")
    assert rel < 1e-2, "ONNX no reproduce a PyTorch"

    # 2) Nuestra normalización + red == inferencia OFICIAL (mismo preprocesado que la app).
    pred = api.inference([img])
    d_api = np.asarray(pred.depth[0], np.float32)
    if d_api.shape != d_onnx[0].shape:
        import cv2
        d_api = cv2.resize(d_api, (W, H), interpolation=cv2.INTER_LINEAR)
    corr = float(np.corrcoef(d_api.ravel(), d_onnx[0].ravel())[0, 1])
    print(f"[export] correlación con model.inference() oficial: {corr:.4f}")
    assert corr > 0.98, "el preprocesado no coincide con el oficial"
    meta = {"model": a.model, "license": "Apache-2.0", "input": [1, 1, 3, H, W], "normalization": "ImageNet",
            "outputs": names, "depth_kind": "relative (DA3 'depth')", "check_onnx_vs_torch": rel, "check_vs_official": corr}
    a.out.with_suffix(".json").write_text(json.dumps(meta, indent=2))
    print("[export] OK", json.dumps(meta))


if __name__ == "__main__":
    main()
