package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.parser.GalleryListParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One-page HTML lookahead. Only fully filtered pages reach the existing list callback. */
public final class GalleryListRequestPipeline {
    private static final class Page {
        final String url;
        final EhRequest template;
        GalleryListParser.Result raw;
        Exception failure;
        EhRequest html;
        EhRequest preparation;
        EhRequest original;
        Page(String url, EhRequest template) { this.url = url; this.template = template; }
    }

    private final Consumer<EhRequest> execute;
    private final Predicate<GalleryListParser.Result> shouldPrefetch;
    private final Map<String, Page> pages = new HashMap<>();
    private final Set<EhRequest> running = new HashSet<>();
    private final Set<EhRequest> originals = new HashSet<>();
    private final Set<String> consumed = new HashSet<>();
    private int generation;

    public GalleryListRequestPipeline(Consumer<EhRequest> execute,
                                     Predicate<GalleryListParser.Result> shouldPrefetch) {
        this.execute = execute;
        this.shouldPrefetch = shouldPrefetch;
    }

    public void execute(EhRequest original) {
        if (original.isCancelled()) return;
        String url = (String) original.getArgs()[0];
        Page page = pages.computeIfAbsent(url, key -> new Page(key, original));
        page.original = original;
        originals.add(original);
        original.setCancelAction(() -> {
            if (page.html != null) page.html.cancel();
            if (page.preparation != null) page.preparation.cancel();
            originals.remove(original);
            pages.remove(url);
        });
        if (page.failure != null) finish(page, null, page.failure);
        else if (page.raw != null) prepare(page);
        else if (page.html == null) fetch(page);
    }

    private EhRequest request(Page page, int method) {
        return new EhRequest().setMethod(method).setBulk(true)
                .setBackground(page.template.isBackground()).setEhConfig(page.template.getEhConfig());
    }

    private void fetch(Page page) {
        Object[] args = page.template.getArgs();
        int epoch = generation;
        EhRequest html = request(page, EhClient.METHOD_GET_GALLERY_LIST_HTML)
                .setArgs(page.url, args[1], args.length > 2 && Boolean.TRUE.equals(args[2]));
        page.html = html;
        running.add(html);
        html.setCallback(new EhClient.Callback<GalleryListParser.Result>() {
            @Override public void onSuccess(GalleryListParser.Result result) {
                running.remove(html);
                if (epoch != generation || html.isCancelled()) return;
                page.html = null;
                page.raw = result;
                if (page.original != null) prepare(page);
            }
            @Override public void onFailure(Exception error) {
                running.remove(html);
                if (epoch != generation || html.isCancelled()) return;
                page.html = null;
                page.failure = error;
                if (page.original != null) finish(page, null, error);
            }
            @Override public void onCancel() { running.remove(html); }
        });
        execute.accept(html);
    }

    private void prepare(Page page) {
        if (page.original.isCancelled()) return;
        pages.remove(page.url);
        consumed.add(page.url);
        String next = page.raw.nextHref;
        if (page.raw.customErrorString == null && next != null && next.startsWith("http")
                && !consumed.contains(next) && !pages.containsKey(next) && shouldPrefetch.test(page.raw)) {
            Page following = new Page(next, page.template);
            pages.put(next, following);
            fetch(following);
        }
        Object[] args = page.template.getArgs();
        int epoch = generation;
        EhRequest preparation = request(page, EhClient.METHOD_PREPARE_GALLERY_LIST)
                .setArgs(page.url, args[1], args.length > 2 && Boolean.TRUE.equals(args[2]),
                        args.length > 3 && Boolean.TRUE.equals(args[3]),
                        args.length > 4 ? args[4] : 0, page.raw);
        page.preparation = preparation;
        running.add(preparation);
        preparation.setCallback(new EhClient.Callback<GalleryListParser.Result>() {
            @Override public void onSuccess(GalleryListParser.Result result) {
                running.remove(preparation);
                if (epoch == generation && !preparation.isCancelled()) finish(page, result, null);
            }
            @Override public void onFailure(Exception error) {
                running.remove(preparation);
                if (epoch == generation && !preparation.isCancelled()) finish(page, null, error);
            }
            @Override public void onCancel() { running.remove(preparation); }
        });
        execute.accept(preparation);
    }

    @SuppressWarnings("unchecked")
    private void finish(Page page, GalleryListParser.Result result, Exception error) {
        EhRequest original = page.original;
        originals.remove(original);
        original.setCancelAction(null);
        if (original.isCancelled()) return;
        EhClient.Callback<GalleryListParser.Result> callback = original.getCallback();
        if (error == null) callback.onSuccess(result);
        else callback.onFailure(error);
    }

    public void cancel() {
        generation++;
        for (EhRequest original : new ArrayList<>(originals)) original.cancel();
        for (EhRequest request : new ArrayList<>(running)) request.cancel();
        originals.clear();
        running.clear();
        pages.clear();
        consumed.clear();
    }
}
