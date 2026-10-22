# FPS & Temp Monitor (Poco X6 Pro)

Ventana flotante y discreta con **FPS en tiempo real** y **temperaturas** (CPU, GPU, batería, piel),
que sigue visible sobre cualquier app o juego.

## Compilar
**Opción A – Android Studio:** abrir esta carpeta → esperar la sincronización → *Build › Build APK*.
**Opción B – GitHub:** subir la carpeta a un repositorio; la pestaña *Actions › Build APK* genera
`FpsTempMonitor-debug` (contiene `app-debug.apk`) en cada push.

## Instalar y configurar (una vez)
1. Instala el APK y ábrelo.
2. Concede *Mostrar sobre otras apps* y notificaciones.
3. Con el teléfono por USB y *Depuración USB* activada, en el PC:
   `adb shell pm grant com.fpsmonitor.app android.permission.DUMP`
   (necesario para leer FPS de otras apps; sin esto solo se ven temperaturas).
4. Pulsa **Iniciar monitor**. Arrastra la ventana donde quieras.
5. HyperOS: Ajustes › Apps › FPS & Temp Monitor → Ahorro de batería «Sin restricciones», Inicio automático ON,
   y bloquea la app en Recientes.

## Cómo funciona
- **FPS:** `dumpsys SurfaceFlinger --latency <capa>` sobre la capa de la app en primer plano (juegos con SurfaceView incluidos).
  Pantalla estática = 0 FPS.
- **Temperaturas:** `dumpsys thermalservice` (CPU/GPU/piel/batería), con respaldo en `/sys/class/thermal` y `BatteryManager`.
- Servicio en primer plano + `TYPE_APPLICATION_OVERLAY`; el muestreo se pausa con la pantalla apagada.
