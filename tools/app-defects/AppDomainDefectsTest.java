import agent.next.app.calendar.CalendarStore;
import agent.next.app.files.FilesStore;

/** Positive regression for date arithmetic and Unicode folder names. */
public final class AppDomainDefectsTest {

    private AppDomainDefectsTest() {
    }

    public static void main(String[] args) {
        CalendarStore.EventSpec spec = CalendarStore.specFor(
                "在日程中23:30添加提醒我夜间会议", "");
        long duration = spec.endMillis() - spec.startMillis();
        if (duration != 3_600_000L) {
            throw new AssertionError("cross-midnight duration=" + duration);
        }
        FilesStore.FolderSpec folder = FilesStore.specFor(
                "在下载目录新建一个名为中文目录的文件夹", "");
        if (!"中文目录".equals(folder.name())) {
            throw new AssertionError("Chinese folder name=" + folder.name());
        }
        System.out.println("AppDomainDefectsTest: PASS (cross-midnight, Chinese folder)");
    }
}
