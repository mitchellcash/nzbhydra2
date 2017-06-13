package org.nzbhydra;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.nzbhydra.config.Category;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.database.IndexerRepository;
import org.nzbhydra.database.SearchResultRepository;
import org.nzbhydra.mapping.newznab.RssRoot;
import org.nzbhydra.migration.FromPythonMigration;
import org.nzbhydra.searching.CategoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.WebSocketAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.system.ApplicationPidFileWriter;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.guava.GuavaCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.awt.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLClassLoader;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Configuration
@EnableAutoConfiguration(exclude = {WebSocketAutoConfiguration.class, AopAutoConfiguration.class, org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration.class})
@ComponentScan
@RestController
@EnableCaching
@EnableScheduling
public class NzbHydra {

    private static final Logger logger = LoggerFactory.getLogger(NzbHydra.class);

    public static String[] originalArgs;

    private static ConfigurableApplicationContext applicationContext;
    private static URLClassLoader urlClassLoader;

    @Autowired
    private ConfigProvider configProvider;
    @Autowired
    private SearchResultRepository searchResultRepository;

    @Autowired
    private IndexerRepository indexerRepository;

    @Autowired
    private CategoryProvider categoryProvider;

    @Autowired
    private RestTemplate restTemplate;

    private static boolean anySettingsOverwritten = false;


    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        parser.accepts("config", "Define path to config yaml file ").withRequiredArg().defaultsTo("config/application.yml");
        parser.accepts("database", "Define path to database base file").withRequiredArg().defaultsTo("database/nzbhydra.db");
        parser.accepts("host", "Run on this host").withRequiredArg();
        parser.accepts("help", "Print help");
        parser.accepts("nobrowser", "Don't open browser to Hydra");
        parser.accepts("port", "Run on this port (default: 5076)").withRequiredArg();
        parser.accepts("version", "Print version");

        OptionSet options = parser.parse(args);
        if (options.has("help")) {
            parser.printHelpOn(System.out);
        } else if (options.has("version")) {
            System.out.println(NzbHydra.class.getPackage().getImplementationVersion());
        } else {
            System.setProperty("spring.config.location", (String) options.valueOf("config"));
            useIfSet(options, "database", "main.databaseFile");
            useIfSet(options, "host", "server.address");
            useIfSet(options, "port", "server.port");
            useIfSet(options, "nobrowser", "main.startupBrowser", "false");

            if (anySettingsOverwritten) {
                logger.warn("Overwritten settings will be displayed with their original value in the config section of the GUI");
            }

            SpringApplication hydraApplication = new SpringApplication(NzbHydra.class);
            hydraApplication.addListeners(new ApplicationPidFileWriter());
            NzbHydra.originalArgs = args;
            if (!options.has("quiet") && !options.has("nobrowser")) {
                hydraApplication.setHeadless(false); //TODO Check, it's better to run headless, perhaps read from args (--quiet or sth)
            }
            applicationContext = hydraApplication.run(args);
        }
    }

    public static ConfigurableApplicationContext start(URLClassLoader urlClassLoader) throws Exception {
        String[] params = new String[]{};
        //main(params);
        SpringApplication hydraApplication = new SpringApplication(NzbHydra.class);
        NzbHydra.urlClassLoader = urlClassLoader;
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader(NzbHydra.urlClassLoader);
        hydraApplication.setResourceLoader(resourceLoader);
        applicationContext = hydraApplication.run();

        return applicationContext;
    }

    public static void shutdown() {
        applicationContext.close();
        try {
            urlClassLoader.loadClass("com.example.demo.NzbHydraWrapper").getDeclaredMethod("doShutdown").invoke(null);
        } catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException | NoSuchMethodException e) {
            logger.error("Error while trying to shutdown", e);
        }
    }

    public static void close() {
        applicationContext.close();
    }

    public static void restart() {
        try {
            urlClassLoader.loadClass("com.example.demo.NzbHydraWrapper").getDeclaredMethod("doRestart").invoke(null);
        } catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException | NoSuchMethodException e) {
            logger.error("Error while trying to restart", e);
        }


    }

    private static void useIfSet(OptionSet options, String optionKey, String propertyName) {
        useIfSet(options, optionKey, propertyName, (String) options.valueOf(optionKey));
    }

    private static void useIfSet(OptionSet options, String optionKey, String propertyName, String propertyValue) {
        if (options.has(optionKey)) {
            System.setProperty(propertyName, propertyValue);
            anySettingsOverwritten = true;
        }
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @EventListener
    protected void startupDone(ApplicationReadyEvent event) {
        //TODO: Possible do all the initializing now / listening to this event where we can be sure that all beans have been constructed
        if (configProvider.getBaseConfig().getMain().isStartupBrowser()) { //TODO Overwritable per command line
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            URI uri = configProvider.getBaseConfig().getBaseUriBuilder().build().toUri();
            logger.info("Opening {} in browser", uri);
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                try {
                    desktop.browse(uri);
                } catch (Exception e) {
                    logger.error("Unable to open browser", e);
                }
            } else {
                logger.error("Unable to open browser");
            }
        }
    }



    @Bean
    public CacheManager getCacheManager() {
        GuavaCacheManager guavaCacheManager = new GuavaCacheManager("infos", "titles", "dev");
        return guavaCacheManager;
    }

    @RequestMapping(value = "/rss")
    public RssRoot get() {
        RssRoot rssRoot = restTemplate.getForObject("http://127.0.0.1:5000/api?apikey=a", RssRoot.class);

        return rssRoot;
    }

    @Autowired
    private FromPythonMigration migration;

    @RequestMapping(value = "/migrate")
    public String delete() throws Exception {
        migration.migrate("http://127.0.0.1:5075/");

        return "Ok";
    }

    @RequestMapping(value = "/categories")
    public String getCats() {
        return categoryProvider.getCategories().stream().map(Category::getName).collect(Collectors.joining(","));

    }

    @RequestMapping("/test")
    @Transactional
    public String test() throws IOException, ExecutionException, InterruptedException {




        return "Ok";
    }


}
