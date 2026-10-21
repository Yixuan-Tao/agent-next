package agent.next.brain.reactive;

import agent.next.brain.session.SubgoalVerifier.Verification;
import agent.next.device.DeviceException;
import agent.next.brain.loop.LoopStoppedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Attempts survive page changes and ambiguous input failures for the entire task. */
final class EffectLedger {
    @FunctionalInterface
    interface Checker { Verification verify(String condition) throws DeviceException, LoopStoppedException; }
    record Gate(boolean allowed, String feedback) { }
    private static final class Entry {
        final BusinessEffect effect;
        int attempts;
        boolean confirmed;
        String checkedPage;
        Entry(BusinessEffect effect) { this.effect = effect; }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    Gate authorize(String app, BusinessEffect effect, String page, Checker checker) throws DeviceException, LoopStoppedException {
        Entry entry = entries.get(id(app, effect));
        if (entry == null) return new Gate(true, "");
        if (entry.confirmed) return blocked(effect, "该结果已独立核验存在，继续下一步并核对数量，不能重复执行");
        if (page.equals(entry.checkedPage)) return blocked(effect, "相同页面尚无新的未生效证据，请去结果页核对");
        Verification present = checker.verify(entry.effect.postcondition());
        if (present.satisfied()) {
            entry.checkedPage = page;
            entry.confirmed = true;
            return blocked(effect, "该结果已独立核验存在：" + present.evidence() + "；继续下一步，不能重复执行");
        }
        // A missing success indicator is ambiguous; only positive evidence of absence permits a retry.
        Verification absent = checker.verify(entry.effect.absenceCondition());
        entry.checkedPage = page;
        if (absent.satisfied() && entry.attempts < 2) {
            return new Gate(true, "独立核验明确未生效，允许一次重试：" + effect.key());
        }
        return blocked(effect, absent.satisfied() ? "已用尽一次重试，请调整方法"
                : "结果未知；未看到成功不代表未生效，先去结果页核对，禁止直接重试");
    }

    void attempted(String app, BusinessEffect effect) {
        Entry entry = entries.computeIfAbsent(id(app, effect), ignored -> new Entry(effect));
        entry.attempts++;
        entry.checkedPage = null;
    }

    void notInjected(String app, BusinessEffect effect) {
        Entry entry = entries.get(id(app, effect));
        if (entry != null && --entry.attempts == 0) entries.remove(id(app, effect));
    }

    List<String> snapshot() {
        List<String> result = new ArrayList<>();
        if (entries.isEmpty()) {
            result.add("副作用记录为空：尚未登记实际业务输入尝试；计划、定位失败或点击前的校验失败都不算已尝试。");
        }
        entries.forEach((id, entry) -> result.add("副作用记录 App/键=" + id + "；尝试=" + entry.attempts
                + "；状态=" + (entry.confirmed ? "已独立证实存在" : "已尝试，结果待核对")
                + "；成功条件=" + entry.effect.postcondition()));
        return result;
    }

    private static Gate blocked(BusinessEffect effect, String detail) {
        return new Gate(false, "阻止重复副作用 " + effect.key() + "：" + detail);
    }

    private static String id(String app, BusinessEffect effect) { return app + ":" + effect.key(); }
}
