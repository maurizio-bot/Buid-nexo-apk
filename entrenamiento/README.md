# Enseñarle a Ultra con tus conversaciones

La memoria de Ultra guarda datos sueltos ("me llamo Maurizio", "mi proyecto se llama X"). El ajuste fino va más allá: cambia el propio cerebro para que hable, razone y conteste como a ti te sirve.

## Cómo funciona

1. **Ultra guarda las conversaciones** en tu teléfono, en `Documentos/Ultra Yo/entrenamiento/conversaciones.jsonl`. Puedes apagarlo o borrarlas en *Ajustes → Cerebro*. Ese archivo no sale del teléfono a menos que tú lo subas.
2. **Entrenas gratis en Google Colab** con el cuaderno [`entrenar_ultra_colab.ipynb`](entrenar_ultra_colab.ipynb): ábrelo en Colab (*Archivo → Subir cuaderno*), elige GPU T4 y ejecuta las celdas en orden. Tu portátil no hace falta.
3. **Copias el resultado al teléfono**, en `Documentos/Ultra Yo/modelos/`, y en *Ajustes → Cerebro* eliges **Propio**.

## Consejos para que sirva de verdad

- Hacen falta bastantes ejemplos: con menos de unas 200 conversaciones apenas se nota.
- Las respuestas del **modo experto** se guardan marcadas y cuentan el doble: Ultra aprende a contestar como el modelo grande.
- Si una respuesta de Ultra fue mala, bórrala del archivo antes de entrenar (es texto, una conversación por línea). Entrenar con respuestas malas la empeora.
- El ajuste se hace sobre el cerebro **ligero (2B)**. El de 4B no cabe en la GPU gratuita de Colab.

## Lo que todavía no está probado

El cuaderno sigue la guía oficial de Google para convertir Gemma 4 a `.litertlm`, pero todavía no se ha ejecutado de principio a fin con tus datos. El paso más delicado es la conversión: usa mucha RAM y en Colab gratis puede cortarse. Si pasa, abre el mismo cuaderno en Kaggle (gratis y con más memoria). Si el modelo propio no arranca en el teléfono, Ultra vuelve sola al cerebro ligero y te dice el error.
