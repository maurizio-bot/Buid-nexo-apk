# Ultra Yo

Tu propia IA, tipo Jarvis, que vive **dentro de tu teléfono Android**. No se conecta a ningún servicio: el modelo (Gemma 4 E2B, abierto, de Google) se ejecuta en el propio teléfono con [LiteRT-LM](https://developers.google.com/edge/litert-lm/overview). Es gratis, privado y funciona sin internet.

## Cómo se usa

1. **Instala la app.** Desde el teléfono, abre la sección **Releases** del repositorio, entra en la versión más reciente y descarga `ultra-yo.apk`. Android pedirá permiso para instalar apps de origen desconocido. Las versiones nuevas se instalan encima sin perder la memoria ni el modelo.
2. **Descarga su cerebro.** La primera vez la app descarga el modelo (unos 2,6 GB, mejor con wifi). Solo se hace una vez.
3. **Háblale.** Toca el reactor (el círculo azul) y pregúntale lo que quieras. Te responde en voz alta.

### Di "Ultra"

Después de cargar el modelo, la app descarga también un "oído" pequeño (unos 40 MB, una sola vez) y se queda escuchando la palabra **"Ultra"**, aunque la cierres o bloquees el teléfono. Verás un aviso fijo de "Ultra Yo te escucha".

- Di "Ultra", espera un instante y habla. Te contesta en voz alta y sigue escuchando, así que puedes conversar sin repetir "Ultra". Cuando te callas, vuelve a esperar la palabra.
- Siempre contesta por voz, también cuando le escribes. Di o escribe **"modo texto"** para que conteste por escrito, y **"modo voz"** para volver.
- Se apaga desde el aviso ("Dejar de escuchar") o en Ajustes → Voz.
- En Samsung, para que Android no la cierre: Ajustes → Aplicaciones → Ultra Yo → Batería → **Sin restricciones**.

La palabra "Ultra" se reconoce dentro del teléfono, sin enviar nada a internet.

### Enséñale

Tu IA aprende lo que le digas y lo guarda en el teléfono:

- "Recuerda que mi proyecto se llama NEXO" → lo apunta y lo tendrá siempre en cuenta.
- "Olvida lo de NEXO" → borra ese recuerdo. "Olvida todo" → borra todos.
- "¿Qué sabes de mí?" → te dice lo que le has enseñado.

En **Memoria** (el engranaje) puedes ver, añadir y borrar lo que sabe, y cambiar su **personalidad**: su nombre, cómo habla y qué hace.

### Que se reescriba a sí mismo

Puedes pedirle que cambie su propia forma de ser y sus reglas:

- "Cámbiate la personalidad: quiero que seas más formal y me llames señor."
- "Créate una habilidad llamada Modo programador: cuando la active, respóndeme con código y explicaciones cortas."
- "Borra tu habilidad Modo programador." / "Borra el recuerdo de NEXO."

Él escribe el cambio y te lo enseña en una tarjeta. **No se aplica hasta que pulses Aplicar.** Si después no te gusta, di "deshaz el último cambio" o usa **Memoria → Deshacer último cambio** (guarda los últimos 20 cambios).

Lo que cambia es su "mente" (personalidad, habilidades y memoria), no el programa de la app: eso solo cambia con una versión nueva.

### Escríbele

Si no puedes hablar, escribe en la barra de abajo y pulsa la flecha de enviar. Cuando le escribes, responde solo por escrito.

Con el botón **+** puedes adjuntar una **foto** (te dice qué ve) o un **archivo de texto** (código, notas, .txt, .md, .json…). Solo los lee, nunca los cambia.

### Pregúntale a Claude

Di o escribe "pregúntale a Claude …" y Ultra Yo abre la app de Claude (o claude.ai si no la tienes) con la pregunta ya escrita. Es gratis: usa tu cuenta normal, sin API ni clave. La respuesta la ves tú en la app de Claude.

### Acceso al teléfono (solo lectura)

- **Apps:** puede decirte qué apps tienes y abrirlas ("abre WhatsApp").
- **Archivos:** en **Memoria → Dar acceso a una carpeta** eliges qué carpetas puede ver. Puede buscar archivos y leer los de texto ("¿qué pone en notas.txt?"). Nunca cambia ni borra nada, y puedes quitarle el acceso cuando quieras.
- Con el teléfono bloqueado no puede usar ni apps ni archivos.

### Abrirla con el botón lateral (Samsung)

Ajustes → Funciones avanzadas → Botón lateral → Pulsar dos veces → Abrir app → Ultra Yo.

La app se muestra encima de la pantalla de bloqueo y empieza a escuchar sola. Con el teléfono bloqueado, la memoria no se puede ver ni cambiar.

## Próximos pasos

- Que pueda hacer cosas en el teléfono: alarmas, recordatorios, abrir apps.
