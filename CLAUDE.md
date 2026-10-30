# android-pos

App de caja (Compose): cobra con el PINpad, autoriza contra gateway-mock, reversa ante timeout y hace store-and-forward cifrado con Keystore.

Es parte del laboratorio de adquirencia: cinco repos hermanos que se clonan en la misma carpeta (ver `LEEME-PRIMERO.md` en la carpeta padre).

## Específico de este repo

- El dominio (`domain/`) es Kotlin puro: `TransactionMachine`, `ChargeUseCase`, `Forwarder`. Se prueba con `./gradlew testDebugUnitTest` sin emulador.
- La app nunca tiene llaves: `MessageFactory` arma el mensaje y el PINpad calcula el MAC.
- La transacción se guarda en `AUTHORIZING` **antes** de mandar nada (write-ahead); al arrancar, `recover()` reversa lo que quedó así.
- La cola (`outbox`) guarda el mensaje firmado exacto, cifrado con AES-GCM del Keystore, y se reenvía siempre con los mismos bytes.
- `pciScan` corre antes de cada build y falla con un PAN literal o un log de datos sensibles. Si un caso es legítimo, `// pci:allow` en esa línea, con motivo.
- Desde el emulador, la máquina de desarrollo es `10.0.2.2` (gateway 8583, admin 8080, PINpad TCP 9100).

## Reglas duras del laboratorio (aplican a todos los repos)

1. **Nunca loguear ni persistir PAN completo, pista 2, PIN ni PIN block.** Solo PAN enmascarado (6 primeros + 4 últimos). Los mensajes de error tampoco llevan el valor que falló.
2. **Ninguna llave real.** La única BDK permitida es la de prueba de ANSI X9.24-1 (`0123456789ABCDEFFEDCBA9876543210`). Nada de llaves en recursos ni en `BuildConfig`.
3. **Un timeout nunca es un rechazo.** Se reintenta la trama o la reversa, **nunca el cobro**. Un estado incierto se resuelve con reversa.
4. **No inventar valores de un estándar.** Si una máscara, un tag o un código no se puede contrastar con una fuente o un vector, se deja fuera y se anota en el README como pendiente.
5. **Cada operación criptográfica usa su propia llave DUKPT.** Para firmar algo que no viene de una lectura de tarjeta (reversa, aviso), primero se avanza el KSN.
6. **El dominio no conoce el framework.** Reglas en Kotlin puro y probadas sin red, sin emulador y sin servidor; Ktor, Room y Compose quedan en los bordes.

## Convenciones

- Kotlin 2.2.10, JVM 17, Gradle 9.6 (wrapper incluido). Código, KDoc, tests y commits en español.
- Tests con nombres que se leen como una regla (`@DisplayName` en JUnit 5 o nombres con backticks en JUnit 4).
- Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `build:`, `ci:`.
- Los repos hermanos se consumen por *composite build*: si `../<repo>` existe, `settings.gradle.kts` lo incluye y se compila desde el código. Para cambiar una librería y probarla en la app no hay que publicar nada.
- Antes de cerrar un cambio: `./gradlew build` (o `./gradlew testDebugUnitTest assembleDebug` en `android-pos`) en verde.
