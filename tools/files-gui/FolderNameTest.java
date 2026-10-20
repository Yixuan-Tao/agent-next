import agent.next.app.skill.FolderName;

/** Pure-JVM regression for the pure-GUI folder-name parameter. */
public final class FolderNameTest {

    private FolderNameTest() {
    }

    public static void main(String[] args) {
        check("中文目录".equals(FolderName.parse("在下载目录新建一个名为中文目录的文件夹").orElse(null)),
                "Unicode folder name");
        check(FolderName.parse("新建文件夹").isEmpty(), "missing name must not default");
        check("agent-demo".equals(FolderName.parse("新建名为agent-demo的文件夹").orElse(null)),
                "ASCII folder name");
        System.out.println("FolderNameTest: PASS (Unicode, missing, ASCII)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
