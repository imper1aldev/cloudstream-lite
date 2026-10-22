# Publicar NexoStream

## Firma preparada el 22 de octubre de 2026

Se generó la clave propia de producción con alias `nexostream-release`, RSA de 4096 bits y certificado válido hasta el 9 de marzo de 2054. Huella SHA-256 pública del certificado: `01:F6:3D:33:21:95:77:F2:D6:67:42:4C:02:49:F2:F0:F7:7F:93:7B:56:04:42:ED:63:5D:B8:37:71:6C:42:56`.

Los cuatro secretos de firma indicados abajo quedaron configurados en `imper1aldev/cloudstream-lite`; se verificaron sus nombres mediante la API de GitHub. La clave privada y las contraseñas permanecen fuera del repositorio, en una carpeta con permisos restringidos al propietario y al sistema. El propietario debe conservar una copia privada independiente para recuperación. No regenerar la clave para cada versión.

La preparación y verificación de la clave no ejecutaron Actions ni publicaron releases. La publicación continúa siendo manual.

## Procedimiento de publicación

### Primera publicación desde GitHub

1. Crear el pull request de `dev` hacia `master` y revisar sus cambios. En la descripción indicar la asistencia de IA y la validación realizada, conforme a `AI-POLICY.md`.
2. Fusionar el pull request conservando el historial mediante **Create a merge commit**. No borrar `dev`.
3. Abrir **Actions → NexoStream release → Run workflow**. El workflow debe estar ya en `master`, la rama predeterminada, para aparecer como ejecución manual.
4. Seleccionar la rama **master** y, para la primera versión estable, introducir **channel: stable**, **version_name: 0.1.0**, **version_code: 1**. Si se desea primero una versión de pruebas, elegir `prerelease` en lugar de `stable`; los canales tienen identificadores de aplicación distintos.
5. Pulsar **Run workflow**, esperar a que termine correctamente y abrir **Releases**. La versión estable se publica como `v0.1.0`, con `NexoStream-stable-universal.apk` y su archivo SHA-256. El workflow crea la release; no hace falta crear una release vacía antes.

Subir `dev` o fusionar a `master` no publica por sí solo. En las siguientes publicaciones aumentar siempre el `version_code` del canal: el workflow comprueba su formato/rango, pero no impide todos los casos de reutilización de un código antiguo.

La app usa `com.imper1aldev.nexostream`. Estable y prerelease tienen IDs diferentes; debug añade `.debug`. Los paquetes internos Kotlin y los créditos de CloudStream se conservan. El APK consulta `imper1aldev/cloudstream-lite`; el plugin permanece en `imper1aldev/cs3-build`.

El workflow `NexoStream release` se ejecuta solamente a mano y solamente en este fork. Requiere cuatro secretos propios de GitHub Actions: `NEXO_KEYSTORE_BASE64`, `NEXO_KEY_ALIAS`, `NEXO_KEY_PASSWORD`, `NEXO_STORE_PASSWORD`. Debe usarse siempre la misma clave de producción para actualizar instalaciones de cada canal. No guardar claves, contraseñas ni base64 en Git. El workflow copia la clave a `RUNNER_TEMP` y la retira al terminar; Gradle recibe `SIGNING_STORE_FILE` y los tres `SIGNING_*` correspondientes. No existe fallback a firma debug ni a secretos de CloudStream.

Seleccionar `stable` o `prerelease`, un `version_code` positivo mayor que todas las versiones publicadas de ese canal y un `version_name` explícito como `0.1.0`. Gradle admite `NEXO_VERSION_CODE` y `NEXO_VERSION_NAME`; los valores iniciales públicos están en `gradle/nexostream.properties`. Mantener el código monotónico incluso al reutilizar el tag prerelease. Para compilaciones locales de producción pasar la configuración propia por entorno y usar `--no-configuration-cache`; no incluir secretos en argumentos o archivos versionados.

El workflow genera `NexoStream-stable-universal.apk` o `NexoStream-prerelease-universal.apk`, verifica la firma y genera SHA-256. Estable usa un tag nuevo `v<version_name>` y nunca sobrescribe una release estable publicada. Prerelease mantiene `pre-release` apuntando al commit compilado. Los archivos se cargan mientras la release es borrador y se publican al terminar para evitar ofrecer una mezcla de tag/archivo viejos. Un intento fallido puede dejar un borrador que se reanuda al repetir el workflow; durante la renovación de prerelease ese canal puede quedar temporalmente sin release pública. Omitir actualización se identifica por el ID del APK, de modo que una carga nueva no queda omitida permanentemente.

Los jobs heredados que escriben en infraestructura de `recloudstream` quedan restringidos al repositorio original; lint y pruebas se conservan. Las instalaciones debug y las pruebas locales con clave de desarrollo no prueban la firma de producción. La validación local de scripts y estructura tampoco sustituye una ejecución real de Actions. No se ha disparado este workflow ni creado releases; la configuración posterior de los secretos de firma figura arriba.

Referencias de comandos de publicación: [GitHub CLI release edit](https://cli.github.com/manual/gh_release_edit), [release view](https://cli.github.com/manual/gh_release_view).

La publicación agrega `<!-- nexostream-version-code: N -->` a las notas. Ambos comprobadores Android comparan ese código con el instalado, independientemente del SHA o del nombre visible. Conserva ese marcador al editar una publicación; si falta o está duplicado, la consulta informa un error y no ofrece una versión indeterminada. Los otros consumidores del modelo compartido conservan su política previa mediante el método opcional del repositorio.
