# RM-Web-Page
Página web do RM

## Acesse Online

A página está publicada via GitHub Pages e pode ser acessada diretamente pelo link:

[**https://paulopalaoro.github.io/APP-Radio/Connection.html**](https://paulopalaoro.github.io/APP-Radio/Connection.html)

> A página é um PWA (Progressive Web App) — no mobile é possível instalar diretamente pelo navegador.

---

## App Android (USB OTG)

Para conectar via cabo USB OTG no Android, use o APK nativo (a Web Serial API não funciona no Chrome Android).

### Instalar APK

Baixe a última versão em:
[**Releases → app-debug.apk**](https://github.com/paulopalaoro/APP-Radio/releases/latest)

1. Abra o link acima no navegador do Android
2. Baixe o `app-debug.apk`
3. Vá em **Configurações → Segurança → Fontes desconhecidas** e habilite
4. Abra o arquivo baixado para instalar
5. Conecte o RadioMaster via cabo USB OTG e abra o app

---

## Desenvolvimento

### Pré-requisitos

- Node.js 18+
- Android Studio (com SDK Android instalado)
- JDK 11+ (o Android Studio já inclui o JBR)

### Setup

```bash
# Instalar dependências
npm install

# Sincronizar arquivos web com o projeto Android
npx cap sync android
```

### Build do APK

```bash
cd android

# Windows — usar o JDK do Android Studio
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

O APK gerado fica em:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

> **Nota:** Na primeira execução o Gradle baixa ~150 MB de dependências. Pode demorar alguns minutos.

### Abrir no Android Studio

```bash
npx cap open android
```

### Estrutura do projeto

```
├── Connection.html          # App principal (Web Serial API + Capacitor adapter)
├── www/                     # Cópia dos arquivos web para o Capacitor
├── capacitor.config.json    # Configuração do Capacitor
└── android/
    └── app/src/main/java/br/com/radiomaster/config/
        ├── MainActivity.java      # Activity principal
        └── UsbSerialPlugin.java   # Plugin nativo USB Serial
```
