package agent.next.app.skill;

import android.content.Context;

import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.plan.TaskPlan;

import java.util.List;

/** Explicit composition root for optional domain skills; core runtime depends on this only. */
public final class DomainSkills {

    private DomainSkills() {
    }

    public static List<DomainSkill> create(Context context, ChatCompletionClient client) {
        return List.of(
                new JdDomainSkill(new JdVisualLocator(client)),
                new QuarkDomainSkill(),
                new CalendarDomainSkill(context));
    }

    public static TaskPlan fixedPlan(List<DomainSkill> skills, TaskPlan planned) {
        if (planned == null || skills == null) {
            return planned;
        }
        for (DomainSkill skill : skills) {
            TaskPlan fixed = skill.fixedPlan(planned);
            if (fixed != null) {
                return fixed;
            }
        }
        return planned;
    }
}
