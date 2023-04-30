/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.verbose;

import com.google.gson.JsonObject;
import me.lucko.luckperms.common.cacheddata.result.TristateResult;
import me.lucko.luckperms.common.http.AbstractHttpClient;
import me.lucko.luckperms.common.http.BytebinClient;
import me.lucko.luckperms.common.http.UnsuccessfulRequestException;
import me.lucko.luckperms.common.locale.Message;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.util.DurationFormatter;
import me.lucko.luckperms.common.util.StackTracePrinter;
import me.lucko.luckperms.common.util.gson.GsonProvider;
import me.lucko.luckperms.common.util.gson.JArray;
import me.lucko.luckperms.common.util.gson.JObject;
import me.lucko.luckperms.common.verbose.event.CheckOrigin;
import me.lucko.luckperms.common.verbose.event.MetaCheckEvent;
import me.lucko.luckperms.common.verbose.event.PermissionCheckEvent;
import me.lucko.luckperms.common.verbose.event.VerboseEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.HoverEvent;
import net.luckperms.api.cacheddata.Result;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.query.QueryMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Accepts and processes {@link VerboseEvent}, passed from the {@link VerboseHandler}.
 */
public class VerboseListener {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    // how much data should we store before stopping.
    private static final int DATA_TRUNCATION = 10000;
    // how many lines should we include in each stack trace send as a chat message
    private static final int STACK_TRUNCATION_CHAT = 15;
    // how many lines should we include in each stack trace in the web output
    private static final int STACK_TRUNCATION_WEB = 40;

    private static final StackTracePrinter FILTERING_PRINTER = StackTracePrinter.builder()
            .ignoreClassStartingWith("me.lucko.luckperms.")
            .ignoreClass("java.util.concurrent.CompletableFuture")
            .ignoreClass("java.util.concurrent.ConcurrentHashMap")
            .build();

    private static final StackTracePrinter CHAT_FILTERED_PRINTER = FILTERING_PRINTER.toBuilder()
            .truncateLength(STACK_TRUNCATION_CHAT)
            .build();

    private static final StackTracePrinter CHAT_UNFILTERED_PRINTER = StackTracePrinter.builder()
            .truncateLength(STACK_TRUNCATION_CHAT)
            .build();

    private static final StackTracePrinter WEB_FILTERED_PRINTER = FILTERING_PRINTER.toBuilder()
            .truncateLength(STACK_TRUNCATION_WEB)
            .build();

    private static final StackTracePrinter WEB_UNFILTERED_PRINTER = StackTracePrinter.builder()
            .truncateLength(STACK_TRUNCATION_WEB)
            .build();

    // the time when the listener was first registered
    private final Instant startTime = Instant.now();
    // the sender to notify each time the listener processes a check which passes the filter
    private final Sender notifiedSender;
    // the filter
    private final VerboseFilter filter;
    // if we should notify the sender
    private final boolean notify;
    // the number of events we have processed
    private final AtomicInteger counter = new AtomicInteger(0);
    // the number of events we have processed and accepted, based on the filter rules for this
    // listener
    private final AtomicInteger matchedCounter = new AtomicInteger(0);
    // the events which passed the filter, up to a max size of #DATA_TRUNCATION
    private final List<VerboseEvent> results = new ArrayList<>(DATA_TRUNCATION / 10);

    public VerboseListener(Sender notifiedSender, VerboseFilter filter, boolean notify) {
        this.notifiedSender = notifiedSender;
        this.filter = filter;
        this.notify = notify;
    }

    /**
     * Accepts and processes verbose events.
     *
     * @param event the event to process
     */
    public void acceptEvent(VerboseEvent event) {
        // increment handled counter
        this.counter.incrementAndGet();

        // check if the data passes our filter
        if (!this.filter.evaluate(event)) {
            return;
        }

        // increment the matched filter
        this.matchedCounter.incrementAndGet();

        // record the check, if we have space for it
        if (this.results.size() < DATA_TRUNCATION) {
            this.results.add(event);
        }

        // handle notifications
        if (this.notify) {
            sendNotification(event);
        }
    }

    private void sendNotification(VerboseEvent event) {
        // form a text component from the check trace
        Component component;
        if (event instanceof PermissionCheckEvent) {
            PermissionCheckEvent permissionEvent = (PermissionCheckEvent) event;
            component = Message.VERBOSE_LOG_PERMISSION.build(
                    permissionEvent.getCheckTarget().describe(),
                    permissionEvent.getPermission(),
                    permissionEvent.getResult().result()
            );
        } else if (event instanceof MetaCheckEvent) {
            MetaCheckEvent metaEvent = (MetaCheckEvent) event;
            component = Message.VERBOSE_LOG_META.build(
                    metaEvent.getCheckTarget().describe(),
                    metaEvent.getKey(),
                    String.valueOf(metaEvent.getResult().result())
            );
        } else {
            throw new IllegalArgumentException("Unknown event type: " + event);
        }

        // just send as a raw message
        if (this.notifiedSender.isConsole()) {
            this.notifiedSender.sendMessage(component);
            return;
        }

        // build the hover text
        List<ComponentLike> hover = new ArrayList<>();

        hover.add(Message.VERBOSE_LOG_HOVER_TYPE.build(event.getType().toString()));
        hover.add(Message.VERBOSE_LOG_HOVER_ORIGIN.build(event.getOrigin().name()));

        Result<?, ?> result = event.getResult();

        if (result instanceof TristateResult) {
            TristateResult tristateResult = (TristateResult) result;

            if (tristateResult.processorClass() != null) {
                hover.add(Message.VERBOSE_LOG_HOVER_PROCESSOR.build(tristateResult.processorClassFriendly()));
            }
        }

        Node node = result.node();
        if (node != null) {
            if (node instanceof MetaNode) {
                hover.add(Message.VERBOSE_LOG_HOVER_CAUSE_META.build((MetaNode) node));
            } else {
                hover.add(Message.VERBOSE_LOG_HOVER_CAUSE.build(node));
            }
        }

        if (event.getCheckQueryOptions().mode() == QueryMode.CONTEXTUAL) {
            hover.add(Message.VERBOSE_LOG_HOVER_CONTEXT.build(event.getCheckQueryOptions().context()));
        }

        hover.add(Message.VERBOSE_LOG_HOVER_THREAD.build(event.getCheckThread()));

        hover.add(Message.VERBOSE_LOG_HOVER_TRACE_TITLE.build());

        Consumer<StackTraceElement> printer = StackTracePrinter.elementToString(str -> hover.add(Message.VERBOSE_LOG_HOVER_TRACE_CONTENT.build(str)));
        int overflow;
        if (shouldFilterStackTrace(event)) {
            overflow = CHAT_FILTERED_PRINTER.process(event.getCheckTrace(), printer);
        } else {
            overflow = CHAT_UNFILTERED_PRINTER.process(event.getCheckTrace(), printer);
        }
        if (overflow != 0) {
            hover.add(Message.VERBOSE_LOG_HOVER_TRACE_OVERFLOW.build(overflow));
        }

        // send the message
        HoverEvent<Component> hoverEvent = HoverEvent.showText(Component.join(Component.newline(), hover));
        this.notifiedSender.sendMessage(component.hoverEvent(hoverEvent));
    }

    private static boolean shouldFilterStackTrace(VerboseEvent event) {
        if (event instanceof PermissionCheckEvent) {
            PermissionCheckEvent permissionEvent = (PermissionCheckEvent) event;
            return permissionEvent.getOrigin() == CheckOrigin.PLATFORM_API_HAS_PERMISSION_SET ||
                    permissionEvent.getOrigin() == CheckOrigin.PLATFORM_API_HAS_PERMISSION;
        }
        return false;
    }

    /**
     * Uploads the captured data in this listener to a paste and returns the url
     *
     * @param bytebin the bytebin instance to upload with
     * @return the url
     */
    public String uploadPasteData(BytebinClient bytebin) throws IOException, UnsuccessfulRequestException {
        // retrieve variables
        String startDate = DATE_FORMAT.format(this.startTime);
        String endDate = DATE_FORMAT.format(Instant.now());
        String duration = DurationFormatter.CONCISE.formatString(Duration.between(this.startTime, Instant.now()));
        boolean truncated = this.matchedCounter.get() > this.results.size();

        JObject metadata = new JObject()
                .add("startTime", startDate)
                .add("endTime", endDate)
                .add("duration", duration)
                .add("count", new JObject()
                        .add("matched", this.matchedCounter.get())
                        .add("total", this.counter.get())
                )
                .add("uploader", new JObject()
                        .add("name", this.notifiedSender.getNameWithLocation())
                        .add("uuid", this.notifiedSender.getUniqueId().toString())
                )
                .add("filter", this.filter.toString())
                .add("truncated", truncated);

        JArray data = new JArray();
        for (VerboseEvent event : this.results) {
            data.add(event.toJson(shouldFilterStackTrace(event) ? WEB_FILTERED_PRINTER : WEB_UNFILTERED_PRINTER));
        }
        this.results.clear();

        JsonObject payload = new JObject()
                .add("metadata", metadata)
                .add("data", data)
                .toJson();

        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(bytesOut), StandardCharsets.UTF_8)) {
            GsonProvider.normal().toJson(payload, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return bytebin.postContent(bytesOut.toByteArray(), AbstractHttpClient.JSON_TYPE).key();
    }

    public Sender getNotifiedSender() {
        return this.notifiedSender;
    }

    public int getMatchedCount() {
        return this.matchedCounter.get();
    }
}
