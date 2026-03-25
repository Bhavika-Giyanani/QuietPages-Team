# QuietPages

---

## Step 1 — Install JDK 21

Download from: https://adoptium.net
- Choose **Temurin 21 (LTS)** → **Windows x64 .msi**
- Run the installer (it sets `JAVA_HOME` automatically)

Verify in a terminal:
```
java -version
```
Expected: `openjdk version "21.0.10"`

---

## Step 2 — Install Maven

Download from: https://maven.apache.org/download.cgi
- Download the **Binary zip archive** (`apache-maven-3.9.x-bin.zip`)
- Extract to e.g. `C:\Program Files\Maven\apache-maven-3.9.x`
- Add Maven's `bin` folder to your system PATH:
  1. Search **"Edit the system environment variables"** in Start
  2. Click **Environment Variables**
  3. Under **System variables** → find `Path` → **Edit** → **New**
  4. Paste: `C:\Program Files\Maven\apache-maven-3.9.x\bin`
  5. Click OK on all dialogs
  6. **Open a new terminal** after this step

Verify:
```
mvn -version
```
Expected: `Apache Maven 3.9.x`

---

## Step 3 — Install VS Code Extensions

Open VS Code → Extensions (`Ctrl+Shift+X`) → install both:

- **Extension Pack for Java** — by Microsoft
- **Maven for Java** — by Microsoft

---

## Step 4 — Open the Project

1. Get the project folder
2. **File → Open Folder** → select the `QuietPages` folder (the one containing `pom.xml`)
3. VS Code will prompt to import as a Maven project — click **Yes**
4. Wait for the Java extension to finish indexing (status bar at the bottom shows progress)

---

## Step 5 — Download Dependencies & Compile

Open the integrated terminal (`Ctrl + backtick`) and run:

```
mvn clean compile
```

This downloads all libraries (~50 MB, one time only) and compiles the project.

A successful result ends with:
```
[INFO] BUILD SUCCESS
```

---

## Step 6 — Run the App

```
mvn javafx:run
```

The QuietPages window will open on the Library tab.

> If VS Code's green Run button doesn't work, always use `mvn javafx:run` in the terminal — it works reliably.

---

## Troubleshooting

**`mvn` is not recognized**
Close VS Code completely, reopen it, then try again. The PATH change requires a fresh terminal.

**`java -version` shows the wrong version**
Set `JAVA_HOME` manually:
- Environment Variables → System variables → **New**
- Name: `JAVA_HOME`
- Value: path to your JDK 21 folder, e.g. `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.9-hotspot`

**`BUILD FAILURE` on compile**
Usually a network hiccup. Run `mvn clean compile` again.
