# Ultra Yo

Tu propia IA, tipo Jarvis, que vive **dentro de tu teléfono Android**. No se conecta a ningún servicio: el modelo (Gemma 4 E2B, abierto, de Google) se ejecuta en el propio teléfono con [LiteRT-LM](https://developers.google.com/edge/litert-lm/overview). Es gratis, privado y funciona sin internet.

## Cómo se usa

1. **Instala la app.** Desde el teléfono, abre la sección **Releases** del repositorio, entra en la versión más reciente y descarga `ultra-yo.apk`. Android pedirá permiso para instalar apps de origen desconocido. Las versiones nuevas se instalan encima sin perder la memoria ni el modelo.
2. **Descarga su cerebro.** La primera vez la app descarga el modelo (unos 2,6 GB, mejor con wifi). Solo se hace una vez.
3. **Háblale.** Toca **Hablar** y pregúntale lo que quieras. Te responde en voz alta.

### Enséñale

Tu IA aprende lo que le digas y lo guarda en el teléfono:

- "Recuerda que mi proyecto se llama NEXO" → lo apunta y lo tendrá siempre en cuenta.
- "Olvida lo de NEXO" → borra ese recuerdo. "Olvida todo" → borra todos.
- "¿Qué sabes de mí?" → te dice lo que le has enseñado.

En **Memoria** puedes ver, añadir y borrar lo que sabe, y cambiar su **personalidad**: su nombre, cómo habla y qué hace.

### Escríbele

Si no puedes hablar, escribe en la caja de abajo y pulsa **Enviar**. Cuando le escribes, responde solo por escrito.

### Acceso al teléfono (solo lectura)

- **Apps:** puede decirte qué apps tienes y abrirlas ("abre WhatsApp").
- **Archivos:** en **Memoria → Dar acceso a una carpeta** eliges qué carpetas puede ver. Puede buscar archivos y leer los de texto ("¿qué pone en notas.txt?"). Nunca cambia ni borra nada, y puedes quitarle el acceso cuando quieras.
- Con el teléfono bloqueado no puede usar ni apps ni archivos.

### Abrirla con el botón lateral (Samsung)

Ajustes → Funciones avanzadas → Botón lateral → Pulsar dos veces → Abrir app → Ultra Yo.

La app se muestra encima de la pantalla de bloqueo y empieza a escuchar sola. Con el teléfono bloqueado, la memoria no se puede ver ni cambiar.

## Próximos pasos

- Palabra de activación ("Jarvis") con la pantalla bloqueada, sin tocar nada.
- Que pueda hacer cosas en el teléfono: alarmas, recordatorios, abrir apps.
