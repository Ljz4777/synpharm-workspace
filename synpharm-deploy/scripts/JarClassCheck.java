import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 用 JVM 真加载来校验 jar / class 目录里的字节码是否可用（问题总账 H-21、H-22）。
 *
 * <p><b>为什么不能用 javap。</b>宿主 VS Code 的 Java 语言服务器会重编译并直接覆盖共享的
 * {@code target/classes}，其产物可能出现 {@code Code} 属性长度为 0 的非法方法。
 * 而 {@code javap} 只按属性表读取、<b>不校验 {@code Code_length}</b>，所以坏类在 javap 眼里
 * 完全正常 —— 只有 JVM 真加载、并反射取方法时才会抛出：
 * <pre>
 *   java.lang.ClassFormatError: Invalid method Code length 0 in class file com/...
 * </pre>
 * 这个错误极具误导性：报错类名会随机漂移，且出现位置常常离根因很远
 * （例如在 Spring 的 {@code checkLookupMethods} 阶段表现为 {@code Lookup method resolution failed}）。
 *
 * <p>本工具把目标挂到 {@code URLClassLoader} 上，逐个 {@code Class.forName} 并调用
 * {@code getDeclaredMethods/Fields/Constructors}，走的就是生产里爆错的那条路径。
 *
 * <p><b>用法</b>（容器内，无需先编译）：
 * <pre>
 *   java /tools/JarClassCheck.java &lt;jar 或 class 目录&gt; [更多...]
 * </pre>
 * 退出码 0 = 全部可加载，1 = 有坏类。
 *
 * <p><b>怎么看结果</b>：只有 {@code ClassFormatError} 才是坏字节码（H-21/H-22）。
 * {@code NoClassDefFoundError} 一般是本工具自身缺依赖（校验单个 jar 时必然出现），
 * 属于噪声，可直接过滤：
 * <pre>
 *   java JarClassCheck.java x.jar 2>&1 | grep ClassFormatError
 * </pre>
 *
 * @author SynPharm Team
 */
public final class JarClassCheck {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: java JarClassCheck.java <jar 或 class 目录> [更多...]");
            System.exit(2);
        }

        int total = 0;
        List<String> broken = new ArrayList<>();

        for (String path : args) {
            File file = new File(path);
            if (!file.exists()) {
                System.out.println("跳过（不存在）: " + path);
                continue;
            }
            List<String> classNames = file.isDirectory() ? scanDir(file) : scanJar(file);
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[]{file.toURI().toURL()}, JarClassCheck.class.getClassLoader())) {
                for (String className : classNames) {
                    total++;
                    try {
                        Class<?> type = Class.forName(className, false, loader);
                        type.getDeclaredMethods();
                        type.getDeclaredFields();
                        type.getDeclaredConstructors();
                    } catch (Throwable e) {
                        broken.add(className + "  <- " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }
            if (file.isFile()) {
                // 顺带确认整个流能读完（被截断的 jar 会在这里报）
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    while (in.read(buf) > 0) {
                        // 只为消费整个流
                    }
                }
            }
        }

        System.out.println("检查类数: " + total + "，无法加载: " + broken.size());
        for (String b : broken) {
            System.out.println("  " + b);
        }
        System.exit(broken.isEmpty() ? 0 : 1);
    }

    private static List<String> scanJar(File jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar)) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        }
        return names;
    }

    private static List<String> scanDir(File root) {
        List<String> names = new ArrayList<>();
        collect(root, root, names);
        return names;
    }

    private static void collect(File root, File dir, List<String> names) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(root, child, names);
            } else if (child.getName().endsWith(".class")) {
                String rel = root.toURI().relativize(child.toURI()).getPath();
                names.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
            }
        }
    }

    private JarClassCheck() {
    }
}
