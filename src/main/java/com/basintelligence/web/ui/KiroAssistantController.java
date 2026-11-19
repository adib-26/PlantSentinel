package com.basintelligence.web.ui;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.agent.chat.KiroAssistantService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.ServiceUnavailableException;

/**
 * The Kiro assistant: a conversation with Kiro in the browser. Each message runs Kiro CLI on the server
 * ({@link KiroAssistantService}); the page polls {@code status.json} until the reply is stored. Conversations are
 * private to the engineer who started them; anyone else gets 404.
 */
@Controller
public class KiroAssistantController {

    private static final List<String> EXAMPLES = List.of(
            "Why is PUMP-02 healthy?",
            "Which machines need attention today?",
            "Is COMP-03 overheating, and what should we check first?");

    private final KiroAssistantService assistant;
    private final Formatters fmt;

    public KiroAssistantController(KiroAssistantService assistant, Formatters fmt) {
        this.assistant = assistant;
        this.fmt = fmt;
    }

    @GetMapping("/assistant")
    public String assistant(Authentication auth, Model model) {
        return page(auth, null, model);
    }

    @GetMapping("/assistant/conversations/{id}")
    public String conversation(@PathVariable long id, Authentication auth, Model model) {
        return page(auth, assistant.conversation(auth.getName(), id), model);
    }

    /** Refusals that the engineer can act on (busy, too long, switched off) come back to the page with their text. */
    @PostMapping("/assistant/messages")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.ASSISTANT)
    @PreAuthorize("hasRole('ENGINEER')")
    public String send(@RequestParam(required = false) String message, @RequestParam(required = false) Long conversationId,
            Authentication auth, RedirectAttributes redirect) {
        try {
            long id = assistant.send(auth.getName(), conversationId, message);
            return "redirect:/assistant/conversations/" + id;
        } catch (InvalidStateException | IllegalArgumentException | ServiceUnavailableException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            redirect.addFlashAttribute("draft", message);
            return conversationId == null ? "redirect:/assistant" : "redirect:/assistant/conversations/" + conversationId;
        }
    }

    @GetMapping(path = "/assistant/messages/{id}/status.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public KiroAssistantService.RunStatus status(@PathVariable long id, Authentication auth) {
        return assistant.status(auth.getName(), id);
    }

    private String page(Authentication auth, KiroAssistantService.ConversationView view, Model model) {
        // Kiro's replies, rendered here because templates may not call beans inside th:utext
        java.util.Map<Long, String> replies = new java.util.HashMap<>();
        if (view != null) {
            view.messages().stream().map(KiroAssistantService.MessageView::message)
                    .filter(m -> m.content() != null)
                    .forEach(m -> replies.put(m.id(), fmt.markdown(m.content())));
        }
        model.addAttribute("availability", assistant.availability());
        model.addAttribute("conversations", assistant.conversations(auth.getName()));
        model.addAttribute("view", view);
        model.addAttribute("replies", replies);
        model.addAttribute("examples", EXAMPLES);
        model.addAttribute("maxChars", assistant.maxMessageChars());
        return "assistant";
    }
}
