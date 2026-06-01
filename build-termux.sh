#!/bin/sh
# ═══════════════════════════════════════════════════════
# Laragon Android MVP Builder for Termux (ARM64)
# Servidor web local autocontenido para Android
# ═══════════════════════════════════════════════════════

set -e

echo "╔══════════════════════════════════════╗"
echo "║  Laragon Android MVP Builder         ║"
echo "║  Servidor Web Local para Android     ║"
echo "╚══════════════════════════════════════╝"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── 1. Verificar aapt2 ──
echo ""
echo "[1/10] Verificando aapt2..."
if ! command -v aapt2 >/dev/null 2>&1; then
    echo "   aapt2 no encontrado. Instalando..."
    pkg update -y && pkg install -y aapt2
fi
AAPT2_PATH="$(command -v aapt2)"
echo "   aapt2: $AAPT2_PATH"

# ── 2. Verificar Java ──
echo ""
echo "[2/10] Verificando Java..."
if ! command -v java >/dev/null 2>&1; then
    echo "   Java no encontrado. Instalando..."
    pkg update -y && pkg install -y openjdk-17
fi
JAVA_VER=$(java -version 2>&1 | head -1)
echo "   Java: $JAVA_VER"

# ── 3. Verificar/crear gradle wrapper ──
echo ""
echo "[3/10] Verificando Gradle Wrapper..."

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLEW="./gradlew"

# Make gradlew executable
if [ -f "$GRADLEW" ]; then
    chmod +x "$GRADLEW" 2>/dev/null || true
    echo "   gradlew: encontrado y ejecutable"
else
    echo "   gradlew no encontrado. Creando script..."
    cat > "$GRADLEW" << 'GRADLEW_EOF'
#!/bin/sh
APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "$(dirname "$0")" > /dev/null && pwd )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi
exec "$JAVACMD" \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
GRADLEW_EOF
    chmod +x "$GRADLEW" 2>/dev/null || true
    echo "   gradlew: creado"
fi

# Download wrapper jar if missing
if [ ! -f "$WRAPPER_JAR" ] || [ "$(wc -c < "$WRAPPER_JAR" 2>/dev/null 2>/dev/null || echo 0)" -lt 1000 ]; then
    echo "   gradle-wrapper.jar no encontrado. Descargando..."
    mkdir -p gradle/wrapper

    # Metodo 1: Descarga directa desde GitHub
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar"
    echo "   Intentando descarga directa..."

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL" 2>/dev/null || true
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL" 2>/dev/null || true
    fi

    # Metodo 2: Extraer de la distribucion completa de Gradle
    if [ ! -f "$WRAPPER_JAR" ] || [ "$(wc -c < "$WRAPPER_JAR" 2>/dev/null || echo 0)" -lt 1000 ]; then
        echo "   Descargando Gradle 8.5 para extraer wrapper jar..."
        GRADLE_VER="8.5"
        ALT_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip"
        TEMP_ZIP="/tmp/gradle-${GRADLE_VER}-bin.zip"

        if command -v curl >/dev/null 2>&1; then
            curl -fsSL -o "$TEMP_ZIP" "$ALT_URL"
        elif command -v wget >/dev/null 2>&1; then
            wget -q -O "$TEMP_ZIP" "$ALT_URL"
        fi

        if [ -f "$TEMP_ZIP" ]; then
            echo "   Extrayendo wrapper jar de la distribucion..."
            cd /tmp
            unzip -q -o "$TEMP_ZIP" "gradle-${GRADLE_VER}/lib/gradle-wrapper-*.jar" 2>/dev/null || true
            WRAPPER_JAR_EXTRACTED=$(find /tmp/gradle-${GRADLE_VER} -name "gradle-wrapper-*.jar" 2>/dev/null | head -1)
            if [ -n "$WRAPPER_JAR_EXTRACTED" ] && [ -f "$WRAPPER_JAR_EXTRACTED" ]; then
                cp "$WRAPPER_JAR_EXTRACTED" "$SCRIPT_DIR/$WRAPPER_JAR"
                echo "   Wrapper jar extraido correctamente"
            fi
            rm -rf /tmp/gradle-${GRADLE_VER} "$TEMP_ZIP"
            cd "$SCRIPT_DIR"
        fi
    fi

    # Metodo 3: Usar gradle del sistema
    if [ ! -f "$WRAPPER_JAR" ] || [ "$(wc -c < "$WRAPPER_JAR" 2>/dev/null || echo 0)" -lt 1000 ]; then
        echo "   Intentando con gradle del sistema..."
        if command -v gradle >/dev/null 2>&1; then
            gradle wrapper --gradle-version 8.5
            echo "   Wrapper generado con gradle del sistema"
        else
            echo "   Instalando gradle temporalmente..."
            pkg install -y gradle 2>/dev/null || true
            if command -v gradle >/dev/null 2>&1; then
                gradle wrapper --gradle-version 8.5
                echo "   Wrapper generado correctamente"
            else
                echo ""
                echo "   ERROR: No se pudo obtener gradle-wrapper.jar"
                echo "   Ejecuta manualmente: pkg install gradle && gradle wrapper"
                exit 1
            fi
        fi
    fi
fi

# Verify wrapper jar
if [ -f "$WRAPPER_JAR" ]; then
    JAR_SIZE=$(wc -c < "$WRAPPER_JAR")
    echo "   gradle-wrapper.jar: ${JAR_SIZE} bytes"
else
    echo "   ERROR: gradle-wrapper.jar no encontrado"
    exit 1
fi

# ── 4. Verificar gradle-wrapper.properties ──
echo ""
echo "[4/10] Verificando gradle-wrapper.properties..."
if [ ! -f "gradle/wrapper/gradle-wrapper.properties" ]; then
    mkdir -p gradle/wrapper
    cat > gradle/wrapper/gradle-wrapper.properties << 'PROPS_EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPS_EOF
fi
echo "   gradle-wrapper.properties: OK"

# ── 5. Configurar gradle.properties para Termux ──
echo ""
echo "[5/10] Configurando gradle.properties para Termux..."
if grep -q "aapt2FromMavenOverride" gradle.properties 2>/dev/null; then
    sed -i "s|android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$AAPT2_PATH|" gradle.properties
else
    echo "android.aapt2FromMavenOverride=$AAPT2_PATH" >> gradle.properties
fi
echo "   aapt2 override: $AAPT2_PATH"

# ── 6. Verificar/Descargar PHP Binary ──
echo ""
echo "[6/10] Verificando binario php-cgi..."
PHP_CGI_ASSET="app/src/main/assets/bin/php/arm64/php-cgi"

if [ -f "$PHP_CGI_ASSET" ]; then
    PHP_SIZE=$(wc -c < "$PHP_CGI_ASSET")
    # Verificar que no sea el stub (el stub es < 500 bytes)
    if [ "$PHP_SIZE" -lt 500 ]; then
        echo "   Detectado stub placeholder. Se necesita el binario real."
        NEEDS_PHP=1
    else
        # Verificar que sea ELF binary (comienza con 0x7f454c46)
        MAGIC=$(xxd -l 4 "$PHP_CGI_ASSET" 2>/dev/null | head -1 | awk '{print $2$3$4$5}')
        if [ "$MAGIC" = "7f454c46" ]; then
            PHP_HUMAN_SIZE=$(du -h "$PHP_CGI_ASSET" | cut -f1)
            echo "   php-cgi binario encontrado: $PHP_HUMAN_SIZE"
            NEEDS_PHP=0
        else
            echo "   Archivo encontrado pero no es un binario ELF valido."
            NEEDS_PHP=1
        fi
    fi
else
    echo "   php-cgi no encontrado en assets."
    NEEDS_PHP=1
fi

if [ "$NEEDS_PHP" = "1" ]; then
    echo ""
    echo "   ┌─────────────────────────────────────────────────┐"
    echo "   │  SE REQUIERE EL BINARIO php-cgi (ARM64)         │"
    echo "   │                                                  │"
    echo "   │  Opcion A: Descargar automaticamente             │"
    echo "   │    Ejecuta: ./download_php_binary.sh             │"
    echo "   │                                                  │"
    echo "   │  Opcion B: Desde Termux en tu dispositivo       │"
    echo "   │    1. pkg install php                            │"
    echo "   │    2. Copiar /data/data/com.termux/files/usr/   │"
    echo "   │       bin/php-cgi a:                             │"
    echo "   │       $PHP_CGI_ASSET"
    echo "   │                                                  │"
    echo "   │  Opcion C: Compilar desde fuentes               │"
    echo "   │    https://github.com/termux/termux-packages     │"
    echo "   └─────────────────────────────────────────────────┘"
    echo ""

    # Preguntar si desea intentar la descarga automatica
    printf "   Intentar descarga automatica? [y/N]: "
    read -r AUTO_DOWNLOAD

    if [ "$AUTO_DOWNLOAD" = "y" ] || [ "$AUTO_DOWNLOAD" = "Y" ]; then
        echo "   Ejecutando download_php_binary.sh..."
        if [ -f "download_php_binary.sh" ]; then
            sh download_php_binary.sh
        else
            echo "   Script de descarga no encontrado."
            echo "   Compilando sin PHP (solo archivos estaticos funcionaran)..."
        fi
    else
        echo "   Compilando sin PHP (solo archivos estaticos funcionaran)..."
        echo "   Puedes agregar el binario despues y recompilar."
    fi
fi

# ── 7. Crear directorios necesarios ──
echo ""
echo "[7/10] Preparando directorios de build..."
mkdir -p app/src/main/assets/bin/php/arm64
mkdir -p app/src/main/res/menu

# ── 8. Build info ──
echo ""
echo "[8/10] Informacion de compilacion:"
echo "   Proyecto:  Laragon Android MVP v1.0.0"
echo "   Gradle:    8.5 | AGP: 8.2.2 | Kotlin: 1.9.22"
echo "   Namespace: com.laragon.android"
echo "   compileSdk: 34 | minSdk: 26 | targetSdk: 34"
echo "   ABI:       arm64-v8a (ARM64 unicamente)"
echo "   Server:    Ktor CIO en puerto 8080"
echo "   PHP:       php-cgi (modo CGI, un proceso por request)"
echo ""

# ── 9. Compilar ──
echo "[9/10] Compilando..."
echo "─────────────────────────────────────────────"
"$GRADLEW" assembleDebug
echo "─────────────────────────────────────────────"

# ── 10. Verificar resultado ──
echo ""
echo "[10/10] Verificando resultado..."
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    APK_SIZE=$(du -h "$APK" | cut -f1)
    APK_BYTES=$(wc -c < "$APK")
    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║  COMPILACION EXITOSA                     ║"
    echo "║  APK: $APK_SIZE"

    # Verificar si excede 80 MB
    APK_MB=$((APK_BYTES / 1048576))
    if [ "$APK_MB" -gt 80 ]; then
        echo "║  ADVERTENCIA: APK excede 80 MB ($APK_MB MB)"
        echo "║  Considera reducir assets o habilitar minify"
    else
        echo "║  Tamano OK ($APK_MB MB < 80 MB limite)"
    fi

    echo "║  Ruta: $APK"
    echo "╚══════════════════════════════════════════╝"

    # Copiar a descargas si es posible
    if [ -d "/sdcard/Download" ]; then
        cp "$APK" "/sdcard/Download/LaragonAndroid.apk" 2>/dev/null && \
            echo "APK copiado a /sdcard/Download/LaragonAndroid.apk" || true
    fi

    # Copiar tambien al directorio del proyecto
    cp "$APK" "LaragonAndroid.apk" 2>/dev/null || true

    echo ""
    echo "Para instalar:"
    echo "  adb install $APK"
    echo "  o copia el APK a tu dispositivo"
else
    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║  ERROR: APK no generado                  ║"
    echo "║  Revisa los errores arriba               ║"
    echo "╚══════════════════════════════════════════╝"
    exit 1
fi
