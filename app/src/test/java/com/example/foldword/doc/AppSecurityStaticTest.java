package com.example.foldword.doc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the app's source code and manifest for the privacy promises Fold Word makes:
 * no credential collection, no hidden networking or uploads, and minimal permissions.
 * Gradle runs unit tests with the "app" folder as the working directory.
 */
public class AppSecurityStaticTest {

    private static File root() {
        File f = new File("src/main");
        if (!f.isDirectory()) f = new File("app/src/main");
        assertTrue("Source folder not found from " + new File(".").getAbsolutePath(), f.isDirectory());
        return f;
    }

    private static List<File> files(File dir, String... exts) {
        List<File> out = new ArrayList<>();
        File[] list = dir.listFiles();
        if (list == null) return out;
        for (File f : list) {
            if (f.isDirectory()) out.addAll(files(f, exts));
            else for (String e : exts) if (f.getName().endsWith(e)) out.add(f);
        }
        return out;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void onlyInternetAndNetworkStatePermissions() throws IOException {
        String manifest = read(new File(root(), "AndroidManifest.xml"));
        Set<String> perms = new TreeSet<>();
        Matcher m = Pattern.compile("uses-permission\\s+android:name=\"([^\"]+)\"").matcher(manifest);
        while (m.find()) perms.add(m.group(1));
        assertEquals("[android.permission.ACCESS_NETWORK_STATE, android.permission.INTERNET]", perms.toString());
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""));
        assertTrue(manifest.contains("android:allowBackup=\"false\""));
    }

    @Test
    public void noPasswordFieldsAnywhere() throws IOException {
        for (File f : files(root(), ".xml", ".kt", ".java")) {
            String s = read(f);
            assertFalse("Password field in " + f, s.contains("textPassword") || s.contains("TYPE_TEXT_VARIATION_PASSWORD"));
        }
    }

    @Test
    public void appContainsNoNetworkingOrUploadCode() throws IOException {
        String[] banned = {"HttpURLConnection", "HttpsURLConnection", "java.net.URL(", "okhttp", "Retrofit", "Socket(",
            "graph.microsoft.com", "DownloadManager", "UploadTask"};
        for (File f : files(root(), ".kt", ".java")) {
            String s = read(f);
            for (String b : banned) assertFalse(b + " found in " + f.getName(), s.contains(b));
        }
    }

    @Test
    public void microsoftPagesAreNeverReadOrScripted() throws IOException {
        File web = new File(root(), "java/com/example/foldword/WordWebActivity.kt");
        String s = read(web);
        assertFalse("Must not inject script into Microsoft's pages", s.contains("evaluateJavascript"));
        assertFalse("Must not expose a bridge to Microsoft's pages", s.contains("addJavascriptInterface"));
        assertFalse("Must not read Microsoft cookies", s.contains("getCookie("));
        assertTrue(s.contains("setAcceptThirdPartyCookies(web, false)"));
    }

    @Test
    public void offlineEditorLoadsNothingFromTheNetwork() throws IOException {
        String html = read(new File(root(), "assets/editor/editor.html"));
        assertTrue(html.contains("default-src 'none'"));
        assertFalse(html.contains("http://") || html.contains("https://"));
        String js = read(new File(root(), "assets/editor/editor.js"));
        assertFalse(js.contains("fetch(") || js.contains("XMLHttpRequest") || js.contains("WebSocket") || js.contains("innerHTML ="));
    }

    @Test
    public void networkSecurityConfigBlocksCleartext() throws IOException {
        String cfg = read(new File(root(), "res/xml/network_security_config.xml"));
        assertTrue(cfg.contains("cleartextTrafficPermitted=\"false\""));
        assertFalse(cfg.contains("src=\"user\""));
    }
}
