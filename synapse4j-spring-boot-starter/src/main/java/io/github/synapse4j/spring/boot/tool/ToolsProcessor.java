package io.github.synapse4j.spring.boot.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.tool.MethodTools;
import io.github.synapse4j.tool.Tool;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.apachecommons.CommonsLog;

/**
 * Turns the beans an application marked with {@link Tools} into tools, once every singleton exists:
 * each class is read through the {@link MethodTools} this holds, and its tools land on the chat clients
 * the class names — on every chat client, when it names none.
 *
 * <p>
 * It runs at the end of startup rather than while beans are being created, because a tool is read off a
 * bean's instance and the instance does not exist before then. For the same reason a class is read
 * through its bean's target: a bean the container wrapped in a proxy still yields its tools, and the
 * proxy is what a call runs on.
 *
 * <p>
 * A class naming a chat client no bean answers to fails the startup, rather than leaving its tools
 * silently nowhere. What the tools are — the codec and the customizers behind them — is the
 * {@link MethodTools} handed in, not this class's business.
 *
 * <p>
 * What it places is logged: each chat client and the tools that landed on it at info, and each class
 * and the tools it declared at debug. The lines go through commons-logging — the API Spring itself
 * logs with, bridged to whatever backend the application configured — so they surface wherever the
 * application's own logging does.
 *
 * <p>
 * It is not part of the auto-configuration: an application that marks its tool classes with
 * {@link Tools} adds one, handing it the reader its tools should be built with.
 */
@CommonsLog
@RequiredArgsConstructor
public class ToolsProcessor implements SmartInitializingSingleton {

    /** The reader the tools are built with, its codec and customizers already settled; never {@code null}. */
    @NonNull
    private final MethodTools methodTools;

    /** Where the marked classes and the chat clients are found; never {@code null}. */
    @NonNull
    private final ListableBeanFactory beanFactory;

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, List<Tool>> byClient = toolsByClient();
        Map<String, ChatClient> clients = beanFactory.getBeansOfType(ChatClient.class);
        List<Tool> shared = byClient.getOrDefault("", List.of());
        clients.forEach((name, client) -> {
            List<Tool> tools = new ArrayList<>(shared);
            tools.addAll(byClient.getOrDefault(name, List.of()));
            tools.forEach(client::addDefaultTool);
            log.info("synapse4j: registered " + tools.size() + " tool(s) on chat client '" + name + "'");
        });
        byClient.keySet().stream()
                .filter(name -> !name.isEmpty() && !clients.containsKey(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new SynapseException("no chat client bean named '" + name + "', which a @Tools class names");
                });
    }

    /**
     * Every tool the marked beans declare, grouped by the chat client its class names — the empty name
     * standing for every client.
     */
    private Map<String, List<Tool>> toolsByClient() {
        Map<String, List<Tool>> byClient = new LinkedHashMap<>();
        beanFactory.getBeansWithAnnotation(Tools.class).forEach((beanName, bean) -> {
            Class<?> type = beanFactory.getType(beanName);
            if (type == null) {
                type = bean.getClass();
            }
            Tools tools = AnnotatedElementUtils.findMergedAnnotation(type, Tools.class);
            if (tools == null) {
                return;
            }
            List<Tool> read = methodTools.from(type, bean);
            log.debug("@Tools " + type.getName() + " declares " + read.size() + " tool(s): "
                    + read.stream().map(Tool::name).toList());
            byClient.computeIfAbsent(tools.client(), key -> new ArrayList<>()).addAll(read);
        });
        return byClient;
    }

}
