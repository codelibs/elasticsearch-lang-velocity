package org.codelibs.elasticsearch.velocity.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.exception.VelocityException;
import org.codelibs.elasticsearch.velocity.util.ContextProperties;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.io.UTF8StreamWriter;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.lookup.SearchLookup;

public class VelocityScriptEngineService extends AbstractComponent implements ScriptEngineService {

    public static Setting<Settings> SETTING_SCRIPT_VELOCITY_PROPS = Setting.groupSetting("script.velocity.props.", Property.NodeScope);

    public static Setting<List<String>> SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_FILE =
            Setting.listSetting("script.velocity.context.prop.file", Collections.emptyList(), s -> s, Property.NodeScope);

    public static Setting<TimeValue> SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_INTERVAL =
            Setting.timeSetting("script.velocity.context.prop.interval", TimeValue.MINUS_ONE, Property.NodeScope);

    public static final String NAME = "velocity";

    /**
     * Thread local UTF8StreamWriter to store template execution results in,
     * thread local to save object creation.
     */
    private static ThreadLocal<SoftReference<UTF8StreamWriter>> utf8StreamWriter = new ThreadLocal<>();

    private VelocityEngine velocityEngine;

    private File workDir;

    private final Queue<File> templateFileQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, Object> contextPropMap = new ConcurrentHashMap<>();

    /**
     * If exists, reset and return, otherwise create, reset and return a writer.
     */
    private static UTF8StreamWriter utf8StreamWriter() {
        final SoftReference<UTF8StreamWriter> ref = utf8StreamWriter.get();
        UTF8StreamWriter writer = ref == null ? null : ref.get();
        if (writer == null) {
            writer = new UTF8StreamWriter(1024 * 4);
            utf8StreamWriter.set(new SoftReference<>(writer));
        }
        writer.reset();
        return writer;
    }

    public VelocityScriptEngineService(final Settings settings) {
        super(settings);

        workDir = findWorkDir(settings);

        final Path configPath = Paths.get(Environment.PATH_CONF_SETTING.get(settings));
        final List<String> propList = SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_FILE.get(settings);
        final TimeValue propInterval = SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_INTERVAL.get(settings);
        for (final String propPath : propList) {
            final Path path = configPath.resolve(propPath);
            if (Files.exists(path)) {
                final ContextProperties properties = new ContextProperties(path.toFile());
                contextPropMap.put(properties.getName(), properties);
                if (propInterval.millis() >= 0) {
                    properties.checkInterval = propInterval.millis();
                }
            } else {
                logger.warn("{} is not found.", path);
            }
        }

        final Properties props = new Properties();
        for (final Map.Entry<String, String> entry : SETTING_SCRIPT_VELOCITY_PROPS.get(settings).getAsMap().entrySet()) {
            props.put(entry.getKey(), entry.getValue());
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

    private File findWorkDir(final Settings settings) {
        for (final String path : Environment.PATH_DATA_SETTING.get(settings)) {
            final File vmCacheDir = Paths.get(path, "vm_cache").toFile();
            if (vmCacheDir.isDirectory()) {
                return vmCacheDir;
            } else if (vmCacheDir.exists()) {
                continue;
            } else if (vmCacheDir.mkdirs()) {
                return vmCacheDir;
            }
        }
        throw new VelocityException("Could not create a working directory.");
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
    public String getExtension() {
        return "vm";
    }

    @Override
    public boolean isInlineScriptEnabled() {
        return true;
    }

    @Override
    public Object compile(final String templateName, final String templateSource, final Map<String, String> params) {
        final VelocityScriptTemplate scriptTemplate = new VelocityScriptTemplate(velocityEngine, workDir, templateSource);
        final File templateFile = scriptTemplate.getTemplateFile();
        if (templateFile != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Adding {}", templateFile.getAbsolutePath());
            }
            templateFileQueue.add(templateFile);
        }
        return scriptTemplate;
    }

    @Override
    public ExecutableScript executable(final CompiledScript compiledScript, final Map<String, Object> vars) {
        final Map<String, Object> scriptVars;
        if (!contextPropMap.isEmpty()) {
            scriptVars = new HashMap<>(contextPropMap);
            scriptVars.putAll(vars);
        } else {
            scriptVars = vars;
        }
        return new VelocityExecutableScript((VelocityScriptTemplate) compiledScript.compiled(), scriptVars, logger);
    }

    @Override
    public SearchScript search(final CompiledScript compiledScript, final SearchLookup lookup, final Map<String, Object> vars) {
        throw new UnsupportedOperationException();
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

                template = velocityEngine.getTemplate(templateFile.getName());
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
                velocityEngine.evaluate(context, writer, logTag, script);
            } else {
                template.merge(context, writer);
            }
        }
    }

    private static class VelocityExecutableScript implements ExecutableScript {
        /** Compiled template object. */
        private final VelocityScriptTemplate context;

        /** Parameters to fill above object with. */
        private Map<String, Object> vars;

        private final Logger logger;

        /**
         * @param template the compiled template object
         * @param vars the parameters to fill above object with
         * @param logger
         **/
        public VelocityExecutableScript(final VelocityScriptTemplate context, final Map<String, Object> vars, final Logger logger) {
            this.context = context;
            this.logger = logger;
            if (vars == null) {
                this.vars = Collections.emptyMap();
            } else {
                this.vars = vars;
            }
        }

        @Override
        public void setNextVar(final String name, final Object value) {
            vars.put(name, value);
        }

        @Override
        public Object run() {
            final BytesStreamOutput result = new BytesStreamOutput();
            final UTF8StreamWriter writer = utf8StreamWriter().setOutput(result);

            try {
                context.merge(new VelocityContext(vars), writer);
                writer.flush();
            } catch (final Exception e) {
                throw new ElasticsearchException("Could not execute query template: ", e);
            } finally {
                try {
                    writer.close();
                } catch (final IOException e) {
                    logger.error("Could not execute query template (failed to close writer): ", e);
                }
            }

            final BytesReference bytes = result.bytes();
            if (logger.isDebugEnabled()) {
                logger.debug("output: {}", bytes.utf8ToString());
            }
            return bytes;
        }

        @Override
        public Object unwrap(final Object value) {
            return value;
        }
    }
}
