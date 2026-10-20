package agent.next.app.skill;

import agent.next.brain.model.ChatCompletionClient;

import java.util.List;

/** Explicit composition root for optional domain skills; core runtime depends on this only. */
public final class DomainSkills {

    private DomainSkills() {
    }

    public static List<DomainSkill> create(ChatCompletionClient client) {
        return List.of(
                new QuarkDomainSkill(),
                new CalculatorDomainSkill(client));
    }
}
