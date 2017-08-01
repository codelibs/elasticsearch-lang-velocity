package org.codelibs.elasticsearch.velocity.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.elasticsearch.ElasticsearchException;

public class ContextProperties extends Properties {

    private static final long serialVersionUID = 1L;

    public long checkInterval = 5000L;

    protected volatile long lastChecked = 0L;

    protected volatile long lastModified = 0L;

    protected volatile File propertiesFile;

    protected volatile Properties properties;

    public ContextProperties(final File file) {
        // check path
        if (file == null) {
            throw new ElasticsearchException("file is null.");
        }

        this.propertiesFile = file;
        if (!this.propertiesFile.exists()) {
            throw new ElasticsearchException(propertiesFile.getAbsolutePath() + " does not exist.");
        } else if (!this.propertiesFile.isFile()) {
            throw new ElasticsearchException(propertiesFile.getAbsolutePath() + " is not a file.");
        }
        load();
    }

    public String getName() {
        return propertiesFile.getName().split("\\.")[0];
    }

    public boolean isUpdated() {
        final long now = System.currentTimeMillis();
        if (now - lastChecked < checkInterval) {
            lastChecked = now;
            return false;
        }
        lastChecked = now;

        final long timestamp = propertiesFile.lastModified();
        if (timestamp <= lastModified) {
            return false;
        }

        return true;
    }

    public synchronized void load() {
        properties = AccessController.doPrivileged(new PrivilegedAction<Properties>() {
            @Override
            public Properties run() {
                final Properties prop = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(propertiesFile);
                    lastModified = propertiesFile.lastModified();
                    prop.load(fis);
                } catch (final IOException e) {
                    throw new ElasticsearchException(e);
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (final IOException e) {
                            // ignore
                        }
                    }
                }
                return prop;
            }
        });
    }

    protected Properties getProperties() {
        if (isUpdated()) {
            load();
        }
        return properties;
    }

    public Set<Object> getAsSet(final Object key) {
        final String cacheKey = key + ".set";
        Object value = get(cacheKey);
        if (value != null) {
            return (Set<Object>) value;
        }

        final Set<Object> set = new HashSet<>();
        int counter = 0;
        while ((value = get(key + "." + counter)) != null) {
            set.add(value);
            counter++;
        }
        put(cacheKey, set);
        return set;
    }

    public List<Object> getAsList(final Object key) {
        final String cacheKey = key + ".list";
        Object value = get(cacheKey);
        if (value != null) {
            return (List<Object>) value;
        }

        final List<Object> list = new ArrayList<>();
        int counter = 0;
        while ((value = get(key + "." + counter)) != null) {
            list.add(value);
            counter++;
        }
        put(cacheKey, list);
        return list;
    }

    @Override
    public void clear() {
        getProperties().clear();
    }

    @Override
    public Object clone() {
        final ContextProperties properties = new ContextProperties(propertiesFile);
        properties.checkInterval = checkInterval;
        return properties;
    }

    @Override
    public boolean contains(final Object value) {
        return getProperties().contains(value);
    }

    @Override
    public boolean containsKey(final Object key) {
        return getProperties().containsKey(key);
    }

    @Override
    public boolean containsValue(final Object value) {
        return getProperties().containsValue(value);
    }

    @Override
    public Enumeration<Object> elements() {
        return getProperties().elements();
    }

    @Override
    public Set<java.util.Map.Entry<Object, Object>> entrySet() {
        return getProperties().entrySet();
    }

    @Override
    public boolean equals(final Object o) {
        return getProperties().equals(o);
    }

    @Override
    public Object get(final Object key) {
        return getProperties().get(key);
    }

    @Override
    public String getProperty(final String key, final String defaultValue) {
        return getProperties().getProperty(key, defaultValue);
    }

    @Override
    public String getProperty(final String key) {
        return getProperties().getProperty(key);
    }

    @Override
    public int hashCode() {
        return getProperties().hashCode();
    }

    @Override
    public boolean isEmpty() {
        return getProperties().isEmpty();
    }

    @Override
    public Enumeration<Object> keys() {
        return getProperties().keys();
    }

    @Override
    public Set<Object> keySet() {
        return getProperties().keySet();
    }

    @Override
    public void list(final PrintStream out) {
        getProperties().list(out);
    }

    @Override
    public void list(final PrintWriter out) {
        getProperties().list(out);
    }

    @Override
    public void load(final InputStream inStream) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public void load(final Reader reader) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public void loadFromXML(final InputStream in) throws IOException, InvalidPropertiesFormatException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public Enumeration<?> propertyNames() {
        return getProperties().propertyNames();
    }

    @Override
    public Object put(final Object key, final Object value) {
        return getProperties().put(key, value);
    }

    @Override
    public void putAll(final Map<? extends Object, ? extends Object> t) {
        getProperties().putAll(t);
    }

    @Override
    public Object remove(final Object key) {
        return getProperties().remove(key);
    }

    @Override
    public void save(final OutputStream out, final String comments) {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public Object setProperty(final String key, final String value) {
        return getProperties().setProperty(key, value);
    }

    @Override
    public int size() {
        return getProperties().size();
    }

    @Override
    public void store(final OutputStream out, final String comments) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public void store(final Writer writer, final String comments) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public void storeToXML(final OutputStream os, final String comment, final String encoding) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public void storeToXML(final OutputStream os, final String comment) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation.");
    }

    @Override
    public Set<String> stringPropertyNames() {
        return getProperties().stringPropertyNames();
    }

    @Override
    public String toString() {
        return getProperties().toString();
    }

    @Override
    public Collection<Object> values() {
        return getProperties().values();
    }
}
