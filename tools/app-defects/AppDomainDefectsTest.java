import agent.next.app.calendar.CalendarStore;
import agent.next.app.files.FilesStore;
public class AppDomainDefectsTest {
  public static void main(String[] args) {
    boolean reproduced = false;
    try {
      CalendarStore.EventSpec spec = CalendarStore.specFor("在日程中23:30添加提醒我夜间会议", "");
      long duration = spec.endMillis() - spec.startMillis();
      if (duration != 3_600_000L) throw new AssertionError("duration=" + duration);
      System.out.println("FIXED: cross-midnight duration");
    } catch (AssertionError e) {
      System.out.println("REPRODUCED: cross-midnight duration — " + e.getMessage());
      reproduced = true;
    }
    try {
      FilesStore.FolderSpec spec = FilesStore.specFor("在下载目录新建一个名为中文目录的文件夹", "");
      if (!"中文目录".equals(spec.name())) throw new AssertionError("name=" + spec.name());
      System.out.println("FIXED: Chinese folder name");
    } catch (AssertionError e) {
      System.out.println("REPRODUCED: Chinese folder name — " + e.getMessage());
      reproduced = true;
    }
    System.out.println(reproduced ? "AppDomainDefectsTest: REPRODUCED" : "AppDomainDefectsTest: FIXED");
  }
}
