# Tisma Scanner: user guide

Tisma Scanner turns a real part into an **STL file in millimetres**, ready to print. You take photos around
the part with your phone, and Tisma Cloud builds the part in 3D from them.

*Español: [GUIA.md](GUIA.md)*

The app is in Spanish; the labels below are quoted as the app shows them, with their meaning.

## What you need

- An Android 11 or later phone with Tisma Scanner.
- A Tisma account with a plan that includes 3D scans.
- The printed **scanning mat** (strongly recommended: it gives the real scale and helps with plain parts).
- Good light, diffuse and steady: an overcast day by a window, a light box, or two lamps with diffusers.

## 1. Print the mat

Print the Tisma Scanner mat on whatever sheet you have (A6, A5, A4, A3, Letter, Legal or Tabloid). Print the
**PDF** at **"actual size" or "100 %"**, without "fit to page". Measure the bar at its edge with a ruler
(100 mm; 50 mm on A6): if it is not that, the part's scale will be off by the same proportion.

The mat is a chessboard: 20 mm squares, with a numbered marker in each white square. It is the same on every
sheet (a bigger sheet has more squares), so Tisma Cloud recognises it by itself, whichever sheet you used.
With the markers, the size of
the part comes out within about 0.1 %. Without the mat, the scale comes from the phone's sensors and focus,
within about 5-10 %.

## 2. Prepare the part

- Put it in the middle of the mat. It does not matter that it covers squares: a few visible around it are
  enough. Choose a sheet that sticks out well beyond the part on every side.
- **If it is plain, one colour or shiny**, photogrammetry finds no points to match. Give it texture:
  matting scan spray (it evaporates in a few hours), talcum powder or, if marking it does not matter, dots
  with a marker pen. On shiny parts the spray is a must.
- Focus on the part before you start: the app locks focus and exposure.

## 3. Scan

1. **Sign in**: ⚙ Ajustes (Settings) → **Nube** (Cloud) → **Entrar** (Sign in).
2. At the top right, choose the **Lejos** (far) pass. Press the shutter button to start.
3. **Walk slowly around the part** at 2-3 heights (about 30°, 50° and 70° above the table), with the whole
   part in the frame at about 2-3 times its size. The app takes the photos by itself every few degrees.
   - If **"¡DEMASIADO RÁPIDO!"** (too fast) appears, slow down.
   - Stop for a moment now and then: the phone corrects its sensors while it is still.
4. **Watch the dome** (bottom right). Its centre is the view from above and its edge the views level with
   the table. Grey = missing, yellow = one photo, green = two or more. The hints below it say which sector
   is missing.
5. When the app says **"✓ Listo para enviar"** (ready to send), the Lejos pass is enough. It takes:
   - at least **75 %** of the dome covered,
   - at least **40 photos**,
   - at most **20 %** blurred photos (the app drops blurred ones by itself).

   Until then, the message says what is missing, for example *"Falta (pasada lejos): cobertura 60 %
   (mín. 75 %)"* (missing on the far pass: 60 % coverage, 75 % minimum).
6. **Optional, for more detail**: switch to **Cerca** (close), come 2-3 times closer and go over the parts
   with relief. Do not come more than 3 times closer at once: the close photos have to match the far ones.
7. Press **■ Terminar** (finish).

## 4. Send it to Tisma Cloud

1. Press **☁ Enviar** (send). Wi-Fi is better: a scan takes 0.5 to 2 GB.
2. The upload goes in pieces: **if the connection drops, it carries on by itself** from where it stopped.
   If the app closes while it is preparing the scan, it picks it up when you open it again.
3. You will see "En cola en Tisma Cloud…" (queued) and then the progress of the computation.
4. When it is done, **⬢ Pieza STL** (STL part) appears: share it with your slicer or keep it.

The STL comes with its measures: size (mm), area and, when the mesh is closed, the volume.

### Privacy

Your location **never leaves the phone**. The app records the GPS position of each scan, but that file stays
on the phone: Tisma Cloud only gets the photos and the position of each photo **relative to the others**.
Tisma Cloud refuses a scan that includes the location. The photos carry no location in their EXIF data.

## If something goes wrong

| Message | What to do |
|---|---|
| "No se pudo armar la pieza con estas fotos" (the part could not be built from these photos) | More photos with more overlap, more texture on the part (spray, mat) and better light. |
| "⚠ Poca textura" (little texture) while scanning | The part has no points to match: mat and matting spray. |
| The part comes out bigger or smaller | Check that the mat is printed at 100 % (100 mm bar). |
| "Tu plan tiene que incluir los escaneos 3D" (your plan must include 3D scans) | Change plan in your Tisma account. |
| The upload does not move | Check the connection. The upload carries on by itself from where it stopped. |

## What to expect

- With the mat, texture and good light, at 20-40 cm: details of a few tenths of a millimetre, and a scale
  within about 0.1 % (measured in synthetic tests; to be confirmed with a real printed mat in the beta).
- Without the mat: the scale comes from the phone's sensors (about 5-10 %). A 40 × 30 × 20 mm test box came
  out 41.2 × 31.1 × 20.1 mm.
- Transparent, very shiny or very thin parts are hard for any photogrammetry.
