#!/bin/bash
# Build manual de GiraQuest v0.1.48 sin Gradle (aapt2 + kotlinc + javac + d8 + apksigner).
# Uso: bash build_manual.sh [paso]   (paso: all|res|kotlinc|dex|apk|verify)
# Requiere: Android SDK (ANDROID_SDK_ROOT o ~/Android/Sdk), JDK 17
# (JAVA_HOME) y kotlinc (KOTLINC_HOME o ~/kotlin/kotlinc).
# BuildConfig.VRP_API_KEY se inyecta en tiempo de compilación desde
# manual-build/.api-key (clave opcional para repositorios que la exijan).
# Si el archivo no existe o no es válido, la APK se compila con la clave
# vacía. El archivo nunca se publica: está en .gitignore.
set -u
export JAVA_HOME=${JAVA_HOME:-$HOME/jdk/current}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
BT=$SDK/build-tools/34.0.0
D8BT=$SDK/build-tools/35.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar
MB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$MB")"
APP=$ROOT/app
WORK=$MB/work
GEN=$WORK/gen
STEP=${1:-all}
PKG=es.girarkas.giraquest

say() { echo "### $*"; }

if [ "$STEP" = all ] || [ "$STEP" = prep ]; then
  say "prep: extraer AAR y listas"
  python3 $MB/prep_build.py || exit 1
  say "prep: manifest procesado + BuildConfig"
  python3 - "$APP/src/main/AndroidManifest.xml" "$WORK/AndroidManifest.xml" <<'EOF'
import sys, re
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
data = data.replace("${applicationId}", "es.girarkas.giraquest")
data = re.sub(r'<manifest([^>]*)>', r'<manifest\1 package="es.girarkas.giraquest">', data, count=1)
open(dst, "w", encoding="utf-8").write(data)
print(open(dst, encoding="utf-8").read()[:600])
EOF
  mkdir -p $WORK/gen
  # Inyectar la clave opcional X-API-Key en BuildConfig (se lee de
  # manual-build/.api-key, modo 600; nunca se imprime ni se publica).
  # Sin archivo, clave vacía.
  VRP_API_KEY=""
  API_KEY_FILE=$MB/.api-key
  if [ -f "$API_KEY_FILE" ]; then
    K=$(cat "$API_KEY_FILE")
    case "$K" in
      *[!A-Za-z0-9._-]*|"") echo "AVISO: .api-key inválido, VRP_API_KEY vacío" ;;
      *) VRP_API_KEY="$K"; echo "VRP_API_KEY inyectada (${#K} caracteres)" ;;
    esac
  else
    echo "AVISO: no existe .api-key, VRP_API_KEY vacío"
  fi
  cat > $WORK/BuildConfig.java <<EOF
package es.girarkas.giraquest;
public final class BuildConfig {
    public static final boolean DEBUG = true;
    public static final String APPLICATION_ID = "es.girarkas.giraquest";
    public static final String BUILD_TYPE = "debug";
    public static final int VERSION_CODE = 49;
    public static final String VERSION_NAME = "0.1.48";
    public static final String VRP_API_KEY = "$VRP_API_KEY";
}
EOF
fi

if [ "$STEP" = all ] || [ "$STEP" = res ]; then
  say "merge de recursos (estilo AGP mergeResources)"
  RES_DIRS="$APP/src/main/res"
  while IFS=$'\t' read -r coord dir; do
    if [ -d "$dir/res" ] && [ -n "$(ls -A "$dir/res" 2>/dev/null)" ]; then
      RES_DIRS="$RES_DIRS $dir/res"
    fi
  done < $WORK/aar_dirs.txt
  rm -rf $WORK/merged-res $WORK/compiled && mkdir -p $WORK/compiled
  # shellcheck disable=SC2086
  python3 $MB/merge_res.py $WORK/merged-res $RES_DIRS 2>&1 | tee $WORK/merge_res.log
  say "aapt2 compile: res fusionados"
  $BT/aapt2 compile --dir $WORK/merged-res -o $WORK/compiled/merged-res.zip || exit 1
  say "aapt2 link"
  EXTRA=$(cat $WORK/lib_packages.txt)
  $BT/aapt2 link -o $WORK/unsigned.apk \
    --manifest $WORK/AndroidManifest.xml \
    -I $ANDROID_JAR \
    -A $APP/src/main/assets \
    --java $GEN \
    --extra-packages "$EXTRA" \
    --auto-add-overlay \
    --min-sdk-version 30 --target-sdk-version 32 \
    --version-code 49 --version-name 0.1.48 \
    $WORK/compiled/*.zip || exit 1
  say "R.java generados:"
  find $GEN -name '*.java' | sort
fi

if [ "$STEP" = all ] || [ "$STEP" = kotlinc ]; then
  say "kotlinc"
  CP=$(paste -sd: $WORK/compile_jars.txt)
  rm -rf $WORK/classes && mkdir -p $WORK/classes
  find $APP/src/main/java -name '*.kt' > $WORK/sources.txt
  find $GEN -name '*.java' >> $WORK/sources.txt
  echo "$WORK/BuildConfig.java" >> $WORK/sources.txt
  wc -l $WORK/sources.txt
  # jvmTarget 1.8 (no 17): el d8 de build-tools 32 (R8 3.x) rechaza class
  # files version 61 (Java 17). El build de Gradle en PC usa jvmTarget 17
  # con R8 8.x sin problema; esto es solo para el dex manual aqui.
  # v0.1.21: -J-Xmx3g — el heap por defecto de kotlinc (1/4 de la RAM de
  # la VM, ~1,5 GB) ya no basta para el linker IR con 70 fuentes + AARs
  # (OutOfMemoryError en generateUnboundSymbolsAsDependencies).
  ${KOTLINC_HOME:-$HOME/kotlin/kotlinc}/bin/kotlinc -J-Xmx1300m -no-stdlib -jvm-target 1.8 \
    -cp "$CP" -d $WORK/classes @${WORK}/sources.txt 2>&1 | tee $WORK/kotlinc.log
  test ${PIPESTATUS[0]} -eq 0 || { echo "KOTLINC FALLO (ver $WORK/kotlinc.log)"; exit 1; }
  say "javac: R.java de app y librerias + BuildConfig"
  # v0.1.3: kotlinc IGNORA los .java para codegen (los lee solo para
  # resolver simbolos): por eso ninguna clase R llegaba al dex y TODA
  # AppCompatActivity crasheaba en attachBaseContext con
  # NoClassDefFoundError: androidx.appcompat.R$drawable (log real de Edu,
  # 2026-11-18). javac si compila los R.java; van al mismo classes/.
  find $GEN -name '*.java' > $WORK/java_sources.txt
  echo "$WORK/BuildConfig.java" >> $WORK/java_sources.txt
  # v0.1.5: los shims org.bouncycastle.asn1.{bsi,cms,eac}.*ObjectIdentifiers
  # (.java en src/main/java) también van por javac: kotlinc los ignora
  # para codegen igual que los R.java.
  find $APP/src/main/java -name '*.java' >> $WORK/java_sources.txt
  wc -l $WORK/java_sources.txt
  $JAVA_HOME/bin/javac -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$CP" -d $WORK/classes @${WORK}/java_sources.txt 2>&1 | tee $WORK/javac.log
  test ${PIPESTATUS[0]} -eq 0 || { echo "JAVAC FALLO (ver $WORK/javac.log)"; exit 1; }
  say "clases R compiladas: $(find $WORK/classes -name 'R.class' -o -name 'R$*.class' | wc -l)"
  say "clases compiladas: $(find $WORK/classes -name '*.class' | wc -l)"
fi

if [ "$STEP" = all ] || [ "$STEP" = dex ]; then
  say "d8"
  rm -rf $WORK/dex && mkdir -p $WORK/dex
  # d8 no acepta un directorio con .kotlin_module: empaquetar las clases en jar
  rm -f $WORK/app-classes.jar
  (cd $WORK/classes && jar cf $WORK/app-classes.jar .)
  # shellcheck disable=SC2046
  $D8BT/d8 -JXmx900m --min-api 30 --lib $ANDROID_JAR --output $WORK/dex \
    $WORK/app-classes.jar $(cat $WORK/dex_jars.txt) 2>&1 | tee $WORK/d8.log
  test ${PIPESTATUS[0]} -eq 0 || { echo "D8 FALLO (ver $WORK/d8.log)"; exit 1; }
  ls -lh $WORK/dex/
fi

if [ "$STEP" = all ] || [ "$STEP" = apk ]; then
  say "empaquetar dex + jni, zipalign, firmar"
  OUTDIR=$APP/build/outputs/apk/debug
  mkdir -p $OUTDIR
  cp $WORK/unsigned.apk $WORK/app.apk
  rm -rf $WORK/staging && mkdir -p $WORK/staging
  for dex in $WORK/dex/*.dex; do cp "$dex" $WORK/staging/; done
  while IFS=$'\t' read -r apkpath src; do
    mkdir -p "$WORK/staging/$(dirname "$apkpath")"
    cp "$src" "$WORK/staging/$apkpath"
  done < $WORK/jni_files.txt
  (cd $WORK/staging && zip -q ../app.apk classes*.dex)
  # v0.1.1: las .so SIN comprimir (-0) y alineadas a 16K con align16k.py.
  # La v0.1 las metía comprimidas con alineado 4K: en móviles con páginas
  # de 16K (Android 15/16) eso puede impedir cargar la .so de Conscrypt
  # al arrancar. zipalign 34.0.0 no tiene la opción -P (llegó en la 35),
  # así que el alineado 16K lo hace align16k.py (extra de cabecera con
  # ceros, como zipalign). Sin comprimir + 16K, la .so se puede cargar
  # directa desde la APK y también extraída (extractNativeLibs=true).
  if [ -d $WORK/staging/lib ]; then (cd $WORK/staging && zip -q -0 -r ../app.apk lib); fi
  unzip -l $WORK/app.apk | grep -q 'classes.*\.dex' || { echo "DEX NO INCLUIDO"; exit 1; }
  python3 $MB/align16k.py $WORK/app.apk $WORK/aligned.apk || exit 1
  $BT/zipalign -c -v 4 $WORK/aligned.apk || exit 1
  if [ ! -f ${MB}/debug.keystore ]; then
    keytool -genkeypair -keystore ${MB}/debug.keystore -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" || exit 1
  fi
  # apksigner 34.0.0: con v2+v3 activas emite solo el bloque v3 (v3 sustituye
  # a v2 en su salida); v3 vale desde API 28, la Quest 3 es API 32.
  $BT/apksigner sign --ks ${MB}/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --v1-signing-enabled false \
    --v2-signing-enabled true --v3-signing-enabled true \
    --out $OUTDIR/giraquest-v0.1.48-debug.apk $WORK/aligned.apk || exit 1
  say "APK: $OUTDIR/giraquest-v0.1.48-debug.apk"
  ls -lh $OUTDIR/giraquest-v0.1.48-debug.apk
  md5sum $OUTDIR/giraquest-v0.1.48-debug.apk
fi

if [ "$STEP" = all ] || [ "$STEP" = verify ]; then
  APK=$APP/build/outputs/apk/debug/giraquest-v0.1.48-debug.apk
  say "apksigner verify"
  $BT/apksigner verify -v "$APK" || exit 1
  say "aapt2 dump badging"
  $BT/aapt2 dump badging "$APK" | head -20
  say "contenido"
  unzip -l "$APK" | tail -25
fi
say "FIN paso=$STEP"
