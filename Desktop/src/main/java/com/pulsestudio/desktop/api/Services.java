package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.net.Endpoints;
import com.pulsestudio.desktop.net.WebClient;
import com.pulsestudio.desktop.service.ArticleService;
import com.pulsestudio.desktop.service.FileService;
import com.pulsestudio.desktop.service.GroqClient;
import com.pulsestudio.desktop.service.HuggingFaceService;
import com.pulsestudio.desktop.service.ImageCache;
import com.pulsestudio.desktop.service.NewsService;
import com.pulsestudio.desktop.service.PexelsService;
import com.pulsestudio.desktop.service.ScriptService;
import com.pulsestudio.desktop.service.SystemService;
import com.pulsestudio.desktop.service.ThumbnailService;
import com.pulsestudio.desktop.store.AppPaths;
import com.pulsestudio.desktop.store.SecretStore;
import com.pulsestudio.desktop.store.SettingsStore;

import java.io.IOException;

/** Composición de dependencias (sin framework): un único grafo de servicios compartido por los controladores. */
public final class Services {
    public final AppPaths paths;
    public final SecretStore secrets;
    public final SettingsStore settings;
    public final Endpoints endpoints = new Endpoints();
    public final WebClient web = new WebClient(endpoints);
    public final GroqClient groq = new GroqClient(web, endpoints);
    public final NewsService news = new NewsService(web, endpoints);
    public final ArticleService articles = new ArticleService(web, endpoints);
    public final ScriptService scripts = new ScriptService(groq);
    public final ThumbnailService thumbnails = new ThumbnailService(groq);
    public final HuggingFaceService huggingFace = new HuggingFaceService(web, endpoints);
    public final ImageCache images;
    public final PexelsService pexels;
    public final FileService files;
    public final SystemService system = new SystemService();

    public Services(AppPaths paths) throws IOException {
        this.paths = paths;
        this.secrets = new SecretStore(paths);
        this.settings = new SettingsStore(paths);
        this.images = new ImageCache(paths.cacheDir());
        this.pexels = new PexelsService(web, endpoints, images);
        this.files = new FileService(settings);
    }

    /** Clave guardada de un servicio ("groq", "pexels", "hf"). */
    public String key(String service) throws IOException { return secrets.load().getOrDefault(service, ""); }
}
