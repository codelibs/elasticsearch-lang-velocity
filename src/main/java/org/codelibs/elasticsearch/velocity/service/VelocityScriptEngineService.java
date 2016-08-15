package org.codelibs.elasticsearch.velocity.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.exception.VelocityException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.UTF8StreamWriter;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.lookup.SearchLookup;

public class VelocityScriptEngineService extends AbstractComponent implements
        ScriptEngineService {

    public static String VELOCITY_SCRIPT_PROPS = "script.velocity.props.";

    /**
     * Thread local UTF8StreamWriter to store template execution results in,
     * thread local to save object creation.
     */
    private static ThreadLocal<SoftReference<UTF8StreamWriter>> utf8StreamWriter = new ThreadLocal<>();

    private VelocityEngine velocityEngine;

    private File workDir;

    private Queue<File> templateFileQueue = new ConcurrentLinkedQueue<>();

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

    @Inject
    public VelocityScriptEngineService(final Settings settings,
            final Environment env) {
        super(settings);

        Object workDirPath = settings.get("script.velocity.work_dir");
        if (workDirPath != null) {
            logger.info("script.velocity.work_dir is deprecated.");
        }
        workDir = findWorkDir(env);

        final Properties props = new Properties();
        for (final Map.Entry<String, String> entry : settings
                .getByPrefix(VELOCITY_SCRIPT_PROPS).getAsMap().entrySet()) {
            props.put(entry.getKey(), entry.getValue());
        }

        final String resourceLoader = (String) props.get("resource.loader");
        if (resourceLoader != null) {
            props.put("resource.loader", "WORK_TMPL,ES_TMPL," + resourceLoader);
        } else {
            props.put("resource.loader", "WORK_TMPL,ES_TMPL");
        }

        initPropertyValue(props, "WORK_TMPL.resource.loader.class",
                "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
        initPropertyValue(props, "WORK_TMPL.resource.loader.path",
                workDir.getAbsolutePath());
        initPropertyValue(props, "WORK_TMPL.resource.loader.cache", "true");
        initPropertyValue(props,
                "WORK_TMPL.resource.loader.modificationCheckInterval", "0");

        initPropertyValue(props, "ES_TMPL.resource.loader.class",
                "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
        initPropertyValue(props, "ES_TMPL.resource.loader.path",
                env.configFile().resolve("scripts").toFile().getAbsolutePath());
        initPropertyValue(props, "ES_TMPL.resource.loader.cache", "true");
        initPropertyValue(props,
                "ES_TMPL.resource.loader.modificationCheckInterval", "60");

        initPropertyValue(props, "velocimacro.library.autoreload", "false");
        initPropertyValue(props, "input.encoding", "UTF-8");
        initPropertyValue(props, "output.encoding", "UTF-8");
        initPropertyValue(props, "runtime.log", env.logsFile()
                .resolve("velocity.log").toFile().getAbsolutePath());

        velocityEngine = AccessController.doPrivileged(new PrivilegedAction<VelocityEngine>() {
            @Override
            public VelocityEngine run() {
                VelocityEngine engine = new VelocityEngine(props);
                engine.init();
                return engine;
            }
        });

    }

    private File findWorkDir(final Environment env) {
        for (final Path path : env.dataFiles()) {
            File vmCacheDir = path.resolve("vm_cache").toFile();
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

    private boolean initPropertyValue(final Properties props, final String key,
            final String value) {
        if (!props.containsKey(key)) {
            props.put(key, value);
            return true;
        }
        return false;
    }

    @Override
    public String[] types() {
        return new String[] { "velocity" };
    }

    @Override
    public String[] extensions() {
        return new String[] { "vm", "velocity" };
    }

    @Override
    public boolean sandboxed() {
        return false;
    }

    @Override
    public Object compile(final String script, final Map<String, String> params) {
        final VelocityScriptTemplate scriptTemplate = new VelocityScriptTemplate(
                velocityEngine, workDir, script);
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
    public ExecutableScript executable(CompiledScript compiledScript,
            Map<String, Object> vars) {
        return new VelocityExecutableScript((VelocityScriptTemplate) compiledScript.compiled(), vars, logger);
    }

    @Override
    public SearchScript search(CompiledScript compiledScript,
            SearchLookup lookup, Map<String, Object> vars) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void scriptRemoved(final CompiledScript script) {
        final Object compiled = script.compiled();
        if (compiled instanceof VelocityScriptTemplate) {
            final File templateFile = ((VelocityScriptTemplate) compiled)
                    .getTemplateFile();
            if (templateFile != null) {
                templateFileQueue.remove(templateFile);
                if (logger.isDebugEnabled()) {
                    logger.debug("Deleting {}", templateFile.getAbsolutePath());
                }
                if (!templateFile.delete()) {
                    logger.warn("Failed to delete {}.",
                            templateFile.getAbsolutePath());
                }
            }
        }
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

        private VelocityEngine velocityEngine;

        public VelocityScriptTemplate(final VelocityEngine velocityEngine,
                final File workDir, final String script) {
            this.velocityEngine = velocityEngine;
            if (script.startsWith("##cache")) {
                String encoding = (String) velocityEngine
                        .getProperty("input.encoding");
                if (encoding == null) {
                    encoding = "UTF-8";
                }

                if (!workDir.exists() && !workDir.mkdirs()) {
                    throw new VelocityException(
                            "Could not create a working directory: "
                                    + workDir.getAbsolutePath());
                }

                templateFile = null;
                BufferedWriter bw = null;
                try {
                    templateFile = File.createTempFile("templ", ".vm", workDir);
                    bw = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(templateFile), encoding));
                    bw.write(script);
                    bw.flush();
                } catch (final IOException e) {
                    throw new VelocityException(
                            "Failed to create a template file.", e);
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
        private VelocityScriptTemplate context;

        /** Parameters to fill above object with. */
        private Map<String, Object> vars;

        private ESLogger logger;

        /**
         * @param template the compiled template object
         * @param vars the parameters to fill above object with
         * @param logger 
         **/
        public VelocityExecutableScript(final VelocityScriptTemplate context,
                final Map<String, Object> vars, final ESLogger logger) {
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
            final UTF8StreamWriter writer = utf8StreamWriter()
                    .setOutput(result);

            try {
                context.merge(new VelocityContext(vars), writer);
                writer.flush();
            } catch (final Exception e) {
                throw new ElasticsearchException("Could not execute query template: ", e);
            } finally {
                try {
                    writer.close();
                } catch (final IOException e) {
                    logger.error(
                            "Could not execute query template (failed to close writer): ",
                            e);
                }
            }

            BytesReference bytes = result.bytes();
            if (logger.isDebugEnabled()) {
                logger.debug("output: {}", new String(bytes.array()));
            }
            return bytes;
        }

        @Override
        public Object unwrap(final Object value) {
            return value;
        }
    }
}
