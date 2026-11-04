# Tisma Scanner: guía de uso

Tisma Scanner convierte una pieza real en un archivo **STL en milímetros** listo para imprimir. Con el
móvil sacas fotos alrededor de la pieza y Tisma Cloud arma la pieza en 3D a partir de ellas.

*English: [GUIDE.md](GUIDE.md)*

## Qué necesitas

- Un móvil Android 11 o posterior con Tisma Scanner.
- Una cuenta de Tisma con un plan que incluya los escaneos 3D.
- El **tapete de escaneo** impreso (muy recomendable: da la escala real y ayuda con piezas lisas).
- Buena luz, difusa y constante: un día nublado junto a una ventana, una caja de luz o dos lámparas con
  difusor.

## 1. Imprime el tapete

Imprime el tapete de Tisma Scanner (A4, A3 o Carta) **al 100 %**, sin «ajustar a la página». Mide con una
regla la barra de 100 mm del borde: si no mide 100 mm, la escala de la pieza saldrá mal en la misma
proporción.

El tapete tiene unos cuadrados blancos y negros (marcadores) que Tisma Cloud reconoce solo. Con ellos, el
tamaño de la pieza sale con un error de alrededor del 0,5 %. Sin tapete, la escala sale de los sensores
del móvil y del enfoque, con un error de alrededor del 5-10 %.

## 2. Prepara la pieza

- Ponla en el centro del tapete, en el hueco que queda entre los marcadores.
- **Si es lisa, de un solo color o brillante**, la fotogrametría no encuentra puntos que emparejar. Dale
  textura: spray de escaneo matificante (se evapora en unas horas), polvo de talco o, si no importa
  marcarla, puntos con rotulador. En piezas brillantes, el spray es imprescindible.
- Enfoca la pieza antes de empezar: la app bloquea el foco y la exposición.

## 3. Escanea

1. **Entra con tu cuenta**: ⚙ Ajustes → **Nube** → **Entrar**.
2. Arriba a la derecha elige la pasada **Lejos**. Pulsa el botón de disparo para empezar.
3. **Rodea la pieza despacio** a 2-3 alturas (unos 30°, 50° y 70° sobre la mesa), con la pieza entera
   en el encuadre a unas 2-3 veces su tamaño. La app dispara sola cada pocos grados.
   - Si aparece **«¡DEMASIADO RÁPIDO!»**, ve más despacio.
   - Párate un instante de vez en cuando: el móvil corrige sus sensores cuando está quieto.
4. **Mira la cúpula** (abajo a la derecha). El centro es la vista desde arriba y el borde, las vistas a
   ras de mesa. Gris = falta, amarillo = una foto, verde = dos o más. Los avisos de debajo dicen qué
   sector falta.
5. Cuando la app diga **«✓ Listo para enviar»**, la pasada Lejos ya es suficiente. Hace falta:
   - al menos un **75 %** de la cúpula cubierto,
   - al menos **40 fotos**,
   - como mucho un **20 %** de fotos movidas (la app descarta sola las movidas).

   Si no está listo, el mensaje dice qué falta, por ejemplo *«Falta (pasada lejos): cobertura 60 %
   (mín. 75 %)»*.
6. **Opcional, para más detalle**: cambia a **Cerca**, acércate 2-3 veces más y repasa las zonas con
   relieve. No te acerques más de 3 veces de golpe: las fotos de cerca tienen que poder emparejarse con
   las de lejos.
7. Pulsa **■ Terminar**.

## 4. Envía a Tisma Cloud

1. Pulsa **☁ Enviar**. Mejor por Wi-Fi: un escaneo ocupa entre 0,5 y 2 GB.
2. La subida va por partes: **si se corta la conexión, sigue sola** donde se quedó. Si la app se cierra
   mientras prepara el escaneo, lo retoma al volver a abrirla.
3. Verás «En cola en Tisma Cloud…» y después el avance del cálculo.
4. Al terminar aparece **⬢ Pieza STL**: compártela con tu programa de laminado o guárdala.

Con el STL llegan sus medidas: tamaño (mm), área y, si la malla queda cerrada, el volumen.

### Privacidad

Tu ubicación **no sale del móvil**. La app guarda la posición GPS de cada escaneo, pero ese archivo se
queda en el teléfono: a Tisma Cloud solo llegan las fotos y la posición de cada foto **respecto de las
demás**. Tisma Cloud rechaza un escaneo que incluya la ubicación. Las fotos no llevan la ubicación en sus
datos EXIF.

## Si algo sale mal

| Mensaje | Qué hacer |
|---|---|
| «No se pudo armar la pieza con estas fotos» | Más fotos con más solape, más textura en la pieza (spray, tapete) y mejor luz. |
| «⚠ Poca textura» durante el escaneo | La pieza no tiene puntos que emparejar: tapete y spray mate. |
| La pieza sale más grande o más chica | Comprueba que el tapete esté impreso al 100 % (barra de 100 mm). |
| «Tu plan tiene que incluir los escaneos 3D» | Cambia de plan en tu cuenta de Tisma. |
| La subida no avanza | Revisa la conexión. La subida sigue sola donde se quedó. |

## Qué se puede esperar

- Con tapete, textura y buena luz, a 20-40 cm: detalles de unas décimas de milímetro y una escala con un
  error de alrededor del 0,5 % (medido en pruebas sintéticas; con un tapete impreso real, a confirmar
  en la beta).
- Sin tapete: la escala sale de los sensores del móvil (alrededor del 5-10 %). En una caja de prueba de
  40 × 30 × 20 mm salió 41,2 × 31,1 × 20,1 mm.
- Las piezas transparentes, muy brillantes o muy finas son difíciles para cualquier fotogrametría.
