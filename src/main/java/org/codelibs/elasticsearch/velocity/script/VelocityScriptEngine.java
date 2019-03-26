package org.codelibs.elasticsearch.velocity.script;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.exception.VelocityException;
import org.codelibs.elasticsearch.velocity.util.ContextProperties;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.TemplateScript;

public class VelocityScriptEngine implements ScriptEngine {

    private static Logger logger = LogManager.getLogger(VelocityScriptEngine.class);

    public static final Setting<Settings> SETTING_SCRIPT_VELOCITY_PROPS =
            Setting.groupSetting("script.velocity.props.", Property.NodeScope);

    public static final Setting<Settings> SETTING_SCRIPT_VELOCITY_CONTEXT_PROPS =
            Setting.groupSetting("script.velocity.context.props.", Property.NodeScope);

    public static final String NAME = "velocity";

    private VelocityEngine velocityEngine;

    private File workDir;

    private final Queue<File> templateFileQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, Object> contextPropMap = new ConcurrentHashMap<>();

    public VelocityScriptEngine(final Settings settings, Path configPath) {

        workDir = findWorkDir(settings);

        final Settings contextPropSettings = settings.getByPrefix(SETTING_SCRIPT_VELOCITY_CONTEXT_PROPS.getKey());
        for (final String key : contextPropSettings.keySet()) {
            if (key.indexOf('.') == -1) {
                final Path path = configPath.resolve(contextPropSettings.get(key));
                if (exists(path)) {
                    final ContextProperties properties = new ContextProperties(path.toFile());
                    contextPropMap.put(key, properties);
                    final String interval = contextPropSettings.get(key + ".interval");
                    if (interval != null) {
                        try {
                            properties.checkInterval = Long.parseLong(interval);
                        } catch (final NumberFormatException e) {
                            logger.warn("{} is not long type.", e, interval);
                        }
                    }
                } else {
                    logger.warn("{} is not found.", path);
                }
            }
        }

        final Properties props = new Properties();
        final Settings velocitySettings = settings.getByPrefix(SETTING_SCRIPT_VELOCITY_PROPS.getKey());
        for (final String key : velocitySettings.keySet()) {
            props.put(key, velocitySettings.get(key));
        }

        final String resourceLoader = (String) props.get("resource.loader");
        if (resourceLoader != null) {
            props.put("resource.loader", "WORK_TMPL,ES_TMPL," + resourceLoader);
        } else {
            props.put("resource.loader", "WORK_TMPL,ES_TMPL");
        }

        initPropertyValue(props, "WORK_TMPL.resource.loader.class", "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
        initPropertyValue(props, "WORK_TMPL.resource.loader.path", workDir.getAbsolutePath());
        initPropertyValue(props, "WORK_TMPL.resource.loader.cache", "true");
        initPropertyValue(props, "WORK_TMPL.resource.loader.modificationCheckInterval", "0");

        initPropertyValue(props, "ES_TMPL.resource.loader.class", "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
        initPropertyValue(props, "ES_TMPL.resource.loader.path", configPath.resolve("scripts").toFile().getAbsolutePath());
        initPropertyValue(props, "ES_TMPL.resource.loader.cache", "true");
        initPropertyValue(props, "ES_TMPL.resource.loader.modificationCheckInterval", "60");

        final Path logsFile = Paths.get(Environment.PATH_LOGS_SETTING.get(settings));
        initPropertyValue(props, "velocimacro.library.autoreload", "false");
        initPropertyValue(props, "input.encoding", "UTF-8");
        initPropertyValue(props, "output.encoding", "UTF-8");
        initPropertyValue(props, "runtime.log", logsFile.resolve("velocity.log").toFile().getAbsolutePath());

        velocityEngine = AccessController.doPrivileged((PrivilegedAction<VelocityEngine>) () -> {
            final VelocityEngine engine = new VelocityEngine(props);
            engine.init();
            return engine;
        });

    }

    private boolean exists(final Path path) {
        return AccessController.doPrivileged((PrivilegedAction<Boolean>) () -> {
            return Files.exists(path);
        });
    }

    private File findWorkDir(final Settings settings) {
        final List<String> lookupPathList = new ArrayList<>();
        List<String> pathList = Environment.PATH_DATA_SETTING.get(settings);
        if (pathList.isEmpty()) {
            pathList = Arrays.asList(new File(Environment.PATH_HOME_SETTING.get(settings), "data").getAbsolutePath());
        }
        for (final String path : pathList) {
            final File vmCacheDir = Paths.get(path, "vm_cache").toFile();
            if (vmCacheDir.isDirectory()) {
                return vmCacheDir;
            } else if (vmCacheDir.exists()) {
                continue;
            } else if (vmCacheDir.mkdirs()) {
                return vmCacheDir;
            }
            lookupPathList.add(vmCacheDir.getAbsolutePath());
        }
        throw new ElasticsearchException(
                "Could not create a working directory: " + String.join(", ", lookupPathList.toArray(new String[lookupPathList.size()])));
    }

    private boolean initPropertyValue(final Properties props, final String key, final String value) {
        if (!props.containsKey(key)) {
            props.put(key, value);
            return true;
        }
        return false;
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    public <T> T compile(final String templateName, final String templateSource, ScriptContext<T> context,
            final Map<String, String> options) {
        final VelocityScriptTemplate scriptTemplate = new VelocityScriptTemplate(velocityEngine, workDir, templateSource);
        final File templateFile = scriptTemplate.getTemplateFile();
        if (templateFile != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Adding {}", templateFile.getAbsolutePath());
            }
            templateFileQueue.add(templateFile);
        }
        TemplateScript.Factory compiled = params -> {
            final Map<String, Object> scriptVars;
            if (!contextPropMap.isEmpty()) {
                scriptVars = new HashMap<>(contextPropMap);
            } else {
                scriptVars = new HashMap<>();
            }
            scriptVars.putAll(params);
            return new VelocityExecutableScript(scriptTemplate, scriptVars);
        };
        return context.factoryClazz.cast(compiled);
    }

    @Override
    public void close() {
        for (final File templateFile : templateFileQueue) {
            if (logger.isDebugEnabled()) {
                logger.debug("Deleting {}", templateFile.getAbsolutePath());
            }
            if (!templateFile.delete()) {
                logger.warn("Failed to delete {}.", templateFile.getAbsolutePath());
            }
        }
    }

    public static class VelocityScriptTemplate {
        private Template template;

        private File templateFile;

        private String script;

        private final VelocityEngine velocityEngine;

        public VelocityScriptTemplate(final VelocityEngine velocityEngine, final File workDir, final String script) {
            this.velocityEngine = velocityEngine;
            if (script.startsWith("##cache")) {
                String encoding = (String) velocityEngine.getProperty("input.encoding");
                if (encoding == null) {
                    encoding = "UTF-8";
                }

                if (!workDir.exists() && !workDir.mkdirs()) {
                    throw new VelocityException("Could not create a working directory: " + workDir.getAbsolutePath());
                }

                templateFile = null;
                BufferedWriter bw = null;
                try {
                    templateFile = File.createTempFile("templ", ".vm", workDir);
                    bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(templateFile), encoding));
                    bw.write(script);
                    bw.flush();
                } catch (final IOException e) {
                    throw new VelocityException("Failed to create a template file.", e);
                } finally {
                    if (bw != null) {
                        try {
                            bw.close();
                        } catch (final IOException e) {
                            // ignore
                        }
                    }
                }

                template = AccessController.doPrivileged((PrivilegedAction<Template>) () -> {
                    return velocityEngine.getTemplate(templateFile.getName());
                });
            } else {
                this.script = script;
            }
        }

        public File getTemplateFile() {
            return templateFile;
        }

        public void merge(final Context context, final Writer writer) {
            if (script != null) {
                final String logTag = Integer.toString(script.hashCode());
                AccessController.doPrivileged((PrivilegedAction<Boolean>) () -> {
                    return velocityEngine.evaluate(context, writer, logTag, script);
                });
            } else {
                AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                    template.merge(context, writer);
                    return null;
                });
            }
        }
    }

    private static class VelocityExecutableScript extends TemplateScript {
        /** Compiled template object. */
        private final VelocityScriptTemplate context;

        /**
         * @param template the compiled template object
         * @param vars the parameters to fill above object with
         **/
        public VelocityExecutableScript(final VelocityScriptTemplate context, final Map<String, Object> vars) {
            super(vars == null ? Collections.emptyMap() : vars);
            this.context = context;
        }

        @Override
        public String execute() {
            try (final StringWriter writer = new StringWriter()) {
                context.merge(new VelocityContext(getParams()), writer);
                writer.flush();

                final String content = writer.toString();
                if (logger.isDebugEnabled()) {
                    logger.debug("output: {}", content);
                }
                return content;
            } catch (final Exception e) {
                throw new ElasticsearchException("Could not execute query template: ", e);
            }
        }
    }

}
