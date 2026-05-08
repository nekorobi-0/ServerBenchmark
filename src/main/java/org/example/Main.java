package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

//TIP コードを<b>実行</b>するには、<shortcut actionId="Run"/> を押すか
// ガターの <icon src="AllIcons.Actions.Execute"/> アイコンをクリックします。
public class Main {
    public static void main(String[] args) {
        Path basePath = Path.of(".").toAbsolutePath().normalize();
        Path tmpDir = basePath.resolve("tmp_" + System.currentTimeMillis());
        try {
            Files.createDirectories(tmpDir);
            System.out.println("Created tmp directory: " + tmpDir);

            // リソースフォルダなどから .index.json ファイルを探す (旧 .mrpack の代わり)
            Path resourcesPath = Path.of("src/main/resources");
            Optional<Path> indexJsonOpt = Optional.empty();
            if (Files.exists(resourcesPath)) {
                try (Stream<Path> stream = Files.walk(resourcesPath, 1)) {
                    indexJsonOpt = stream.filter(p -> p.toString().endsWith(".index.json")).findFirst();
                }
            }
            if (indexJsonOpt.isEmpty()) {
                System.err.println("No *.index.json file found in " + resourcesPath);
                return;
            }
            Path indexJson = indexJsonOpt.get();
            System.out.println("Found index JSON: " + indexJson);

            // サーバー環境構築関数を呼び出す
            setupServerEnvironment(indexJson, tmpDir);

            System.out.println("Installation completed. Starting server...");
            startServerAndAcceptEula(tmpDir);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setupServerEnvironment(Path indexJson, Path targetDir) throws Exception {
        Gson gson = new Gson();
        JsonObject data;
        try (Reader reader = Files.newBufferedReader(indexJson)) {
            data = gson.fromJson(reader, JsonObject.class);
        }

        // Download files
        JsonArray files = data.getAsJsonArray("files");
        for (JsonElement fileElem : files) {
            JsonObject fileObj = fileElem.getAsJsonObject();
            JsonObject env = fileObj.getAsJsonObject("env");
            if (env == null || !env.has("server") || "required".equals(env.get("server").getAsString())) {
                String relativePath = fileObj.get("path").getAsString();
                String downloadUrl = fileObj.getAsJsonArray("downloads").get(0).getAsString();
                Path targetFilePath = targetDir.resolve(relativePath);
                Files.createDirectories(targetFilePath.getParent());
                downloadFile(downloadUrl, targetFilePath);
            }
        }

        // Install mod loader based on dependencies
        JsonObject deps = data.getAsJsonObject("dependencies");
        String mcVer = deps.get("minecraft").getAsString();

        if (deps.has("neoforge")) {
            String neoforgeVer = deps.get("neoforge").getAsString();
            System.out.println("Installing neoforge...");
            String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neoforgeVer + "/neoforge-" + neoforgeVer + "-installer.jar";
            Path installerJar = targetDir.resolve("installer.jar");
            downloadFile(url, installerJar);

            ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerJar.getFileName().toString(), "--installServer");
            pb.directory(targetDir.toFile());
            pb.inheritIO().start().waitFor();
        } else if (deps.has("forge")) {
            String forgeVer = deps.get("forge").getAsString();
            System.out.println("Installing forge...");
            String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + mcVer + "-" + forgeVer + "/forge-" + mcVer + "-" + forgeVer + "-installer.jar";
            Path installerJar = targetDir.resolve("installer.jar");
            downloadFile(url, installerJar);

            ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerJar.getFileName().toString(), "--installServer");
            pb.directory(targetDir.toFile());
            pb.inheritIO().start().waitFor();
        } else if (deps.has("fabric")) {
            String fabricVer = deps.get("fabric").getAsString();
            System.out.println("Installing fabric...");
            String url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.1/fabric-installer-1.0.1.jar";
            Path installerJar = targetDir.resolve("installer.jar");
            downloadFile(url, installerJar);
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerJar.getFileName().toString(), "server", "-loader", fabricVer, "-mcversion", mcVer, "-downloadMinecraft");
            pb.directory(targetDir.toFile());
            pb.inheritIO().start().waitFor();
        }
    }

    public static void startServerAndAcceptEula(Path workDir) throws IOException, InterruptedException {
        System.out.println("Starting Server for the first time...");
        ProcessBuilder pbFirstRun = createServerProcessBuilder(workDir);
        pbFirstRun.directory(workDir.toFile());
        pbFirstRun.start().waitFor(); // EULAでこけるはず

        // EULAを書き換える
        Path eulaPath = workDir.resolve("eula.txt");
        if (Files.exists(eulaPath)) {
            System.out.println("Accepting EULA...");
            String eulaContent = Files.readString(eulaPath);
            eulaContent = eulaContent.replace("eula=false", "eula=true");
            Files.writeString(eulaPath, eulaContent);

            // もう一回起動
            System.out.println("Starting Server again...");
            ProcessBuilder pbSecondRun = createServerProcessBuilder(workDir);
            pbSecondRun.directory(workDir.toFile());
            pbSecondRun.inheritIO().start().waitFor();
        } else {
            System.err.println("eula.txt not found.");
        }
    }

    private static ProcessBuilder createServerProcessBuilder(Path workDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        String scriptName = isWindows ? "run.bat" : "run.sh";
        if (Files.exists(workDir.resolve(scriptName))) {
            return new ProcessBuilder(isWindows ? "cmd.exe" : "sh", isWindows ? "/c" : "-c", isWindows ? scriptName : "./" + scriptName);
        } else {
            // スクリプトがない場合のフォールバック
            return new ProcessBuilder("java", "-jar", "forge-1.20.1-47.4.10.jar"); 
        }
    }

    public static void downloadFile(String urlStr, Path targetPath) {
        System.out.println("Downloading: " + urlStr);
        try (InputStream in = new URL(urlStr).openStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Download complete.");
        } catch (IOException e) {
            System.err.println("Failed to download file.");
            e.printStackTrace();
        }
    }

    public static void unzip(Path sourceZip, Path targetDir) {
        System.out.println("Unzipping " + sourceZip + " to " + targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(sourceZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolvedPath = targetDir.resolve(entry.getName()).normalize();
                if (!resolvedPath.startsWith(targetDir)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    if (resolvedPath.getParent() != null) {
                        Files.createDirectories(resolvedPath.getParent());
                    }
                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void move_resources(){
        List<String> resourceToMove = List.of("src/main/resources/config.properties", "src/main/resources/data.csv");
        resourceToMove.forEach((String pathString)->{
            Path path = Path.of(pathString);
            Path dist = Path.of("dist");
            move_resource(path,dist);
        });
    }
    public static void move_resource(Path location, Path dist){
        try {
            // 移動先のディレクトリが存在しない場合は作成する
            if (!Files.exists(dist)) {
                Files.createDirectories(dist);
            }

            // 移動先の完全なファイルパスを組み立てる (例: dist/config.properties)
            Path targetPath = dist.resolve(location.getFileName());

            // ファイルを移動する。すでに存在する場合は上書きする。
            Files.move(location, targetPath, StandardCopyOption.REPLACE_EXISTING);

            System.out.println("移動が完了しました: " + location + " -> " + targetPath);
        } catch (IOException e) {
            System.err.println("ファイルの移動中にエラーが発生しました: " + location);
            e.printStackTrace();
        }
    }
}
