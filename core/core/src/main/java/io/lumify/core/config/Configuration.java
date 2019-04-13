package io.lumify.core.config;

import io.lumify.core.bootstrap.InjectHelper;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.ontology.Concept;
import io.lumify.core.model.ontology.OntologyProperty;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.ontology.Relationship;
import io.lumify.core.util.ClassUtil;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.commons.beanutils.ConvertUtilsBean;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Responsible for parsing application configuration file and providing
 * configuration values to the application
 */
public class Configuration {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(Configuration.class);
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    public static final String BASE_URL = "base.url";
    public static final String HADOOP_URL = "hadoop.url";
    public static final String HDFS_LIB_CACHE_SOURCE_DIRECTORY = "hdfsLibcache.sourceDirectory";
    public static final String HDFS_LIB_CACHE_TEMP_DIRECTORY = "hdfsLibcache.tempDirectory";
    public static final String HDFS_LIB_CACHE_HDFS_USER = "hdfsLibcache.user";
    public static final String LIB_DIRECTORY = "lib-directory";
    public static final String ZK_SERVERS = "zookeeper.serverNames";
    public static final String MODEL_PROVIDER = "model.provider";
    public static final String USER_REPOSITORY = "repository.user";
    public static final String WORKSPACE_REPOSITORY = "repository.workspace";
    public static final String AUTHORIZATION_REPOSITORY = "repository.authorization";
    public static final String ONTOLOGY_REPOSITORY = "repository.ontology";
    public static final String AUDIT_REPOSITORY = "repository.audit";
    public static final String ARTIFACT_THUMBNAIL_REPOSITORY = "repository.artifactThumbnail";
    public static final String WORK_QUEUE_REPOSITORY = "repository.workQueue";
    public static final String LONG_RUNNING_PROCESS_REPOSITORY = "repository.longRunningProcess";
    public static final String SYSTEM_NOTIFICATION_REPOSITORY = "repository.systemNotification";
    public static final String WEB_APP_EMBEDDED_LONG_RUNNING_PROCESS_RUNNER_ENABLED = "webAppEmbedded.longRunningProcessRunner.enabled";
    public static final boolean WEB_APP_EMBEDDED_LONG_RUNNING_PROCESS_RUNNER_ENABLED_DEFAULT = true;
    public static final String WEB_APP_EMBEDDED_LONG_RUNNING_PROCESS_RUNNER_THREAD_COUNT = "webAppEmbedded.longRunningProcessRunner.threadCount";
    public static final int WEB_APP_EMBEDDED_LONG_RUNNING_PROCESS_RUNNER_THREAD_COUNT_DEFAULT = 1;
    public static final String WEB_APP_EMBEDDED_GRAPH_PROPERTY_WORKER_RUNNER_ENABLED = "webAppEmbedded.graphPropertyWorkerRunner.enabled";
    public static final boolean WEB_APP_EMBEDDED_GRAPH_PROPERTY_WORKER_RUNNER_ENABLED_DEFAULT = true;
    public static final String WEB_APP_EMBEDDED_GRAPH_PROPERTY_WORKER_RUNNER_THREAD_COUNT = "webAppEmbedded.graphPropertyWorkerRunner.threadCount";
    public static final int WEB_APP_EMBEDDED_GRAPH_PROPERTY_WORKER_RUNNER_THREAD_COUNT_DEFAULT = 1;
    public static final String USER_NOTIFICATION_REPOSITORY = "repository.userNotification";
    public static final String ONTOLOGY_REPOSITORY_OWL = "repository.ontology.owl";
    public static final String GRAPH_PROVIDER = "graph";
    public static final String VISIBILITY_TRANSLATOR = "security.visibilityTranslator";
    public static final String AUDIT_VISIBILITY_LABEL = "audit.visibilityLabel";
    public static final String DEFAULT_PRIVILEGES = "newuser.privileges";
    public static final String WEB_PROPERTIES_PREFIX = "web.ui.";
    public static final String WEB_GEOCODER_ENABLED = WEB_PROPERTIES_PREFIX + "geocoder.enabled";
    public static final String DEV_MODE = "devMode";
    public static final String DEFAULT_SEARCH_RESULT_COUNT = "search.defaultSearchCount";
    public static final String LOCK_REPOSITORY_PATH_PREFIX = "lockRepository.pathPrefix";
    public static final String USER_SESSION_COUNTER_PATH_PREFIX = "userSessionCounter.pathPrefix";
    public static final String DEFAULT_TIME_ZONE = "default.timeZone";
    public static final String RABBITMQ_PREFETCH_COUNT = "rabbitmq.prefetch.count";
    public static final String QUEUE_PREFIX = "queue.prefix";
    private final ConfigurationLoader configurationLoader;
    private final LumifyResourceBundleManager lumifyResourceBundleManager;

    private Map<String, String> config = new HashMap<>();

    public Configuration(final ConfigurationLoader configurationLoader, final Map<?, ?> config) {
        this.configurationLoader = configurationLoader;
        this.lumifyResourceBundleManager = new LumifyResourceBundleManager();
        for (Map.Entry entry : config.entrySet()) {
            if (entry.getValue() != null) {
                set(entry.getKey().toString(), entry.getValue());
            }
        }
    }

    public String get(String propertyKey, String defaultValue) {
        return config.containsKey(propertyKey) ? config.get(propertyKey) : defaultValue;
    }

    public boolean getBoolean(String propertyKey, boolean defaultValue) {
        return Boolean.parseBoolean(get(propertyKey, Boolean.toString(defaultValue)));
    }

    public Integer getInt(String propertyKey, Integer defaultValue) {
        return Integer.parseInt(get(propertyKey, defaultValue == null ? null : defaultValue.toString()));
    }

    public Integer getInt(String propertyKey) {
        return getInt(propertyKey, null);
    }

    public <T> Class<? extends T> getClass(String propertyKey) {
        String className = get(propertyKey, null);
        if (className == null) {
            throw new LumifyException("Could not find required property " + propertyKey);
        }
        try {
            LOGGER.debug("found class \"%s\" for configuration \"%s\"", className, propertyKey);
            return ClassUtil.forName(className);
        } catch (LumifyException e) {
            throw new LumifyException("Could not load class " + className + " for property " + propertyKey, e);
        }
    }

    public Map<String, String> getSubset(String keyPrefix) {
        Map<String, String> subset = new HashMap<>();
        for (Map.Entry<String, String> entry : this.config.entrySet()) {
            if (!entry.getKey().startsWith(keyPrefix + ".") && !entry.getKey().equals(keyPrefix)) {
                continue;
            }
            String newKey = entry.getKey().substring(keyPrefix.length());
            if (newKey.startsWith(".")) {
                newKey = newKey.substring(1);
            }
            subset.put(newKey, entry.getValue());
        }
        return subset;
    }

    public void setConfigurables(Object o, String keyPrefix) {
        Map<String, String> subset = getSubset(keyPrefix);
        setConfigurables(o, subset);
    }

    public void setConfigurables(Object o, Map<String, String> config) {
        ConvertUtilsBean convertUtilsBean = new ConvertUtilsBean();
        Map<Method, PostConfigurationValidator> validatorMap = new HashMap<>();

        for (Method m : o.getClass().getMethods()) {
            Configurable configurableAnnotation = m.getAnnotation(Configurable.class);
            if (configurableAnnotation != null) {
                if (m.getParameterTypes().length != 1) {
                    throw new LumifyException("Invalid method to be configurable. Expected 1 argument. Found " + m.getParameterTypes().length + " arguments");
                }

                String propName = m.getName().substring("set".length());
                if (propName.length() > 1) {
                    propName = propName.substring(0, 1).toLowerCase() + propName.substring(1);
                }

                String name;
                String defaultValue;
                if (configurableAnnotation.name() != null) {
                    name = configurableAnnotation.name();
                    defaultValue = configurableAnnotation.defaultValue();
                } else {
                    name = propName;
                    defaultValue = null;
                }
                String val;
                if (config.containsKey(name)) {
                    val = config.get(name);
                } else {
                    if (Configurable.DEFAULT_VALUE.equals(defaultValue)) {
                        if (configurableAnnotation.required()) {
                            throw new LumifyException("Could not find property " + name + " for " + o.getClass().getName() + " and no default value was specified.");
                        } else {
                            continue;
                        }
                    }
                    val = defaultValue;
                }
                try {
                    Object convertedValue = convertUtilsBean.convert(val, m.getParameterTypes()[0]);
                    m.invoke(o, convertedValue);
                } catch (Exception ex) {
                    throw new LumifyException("Could not set property " + m.getName() + " on " + o.getClass().getName());
                }
            }

            PostConfigurationValidator validatorAnnotation = m.getAnnotation(PostConfigurationValidator.class);
            if (validatorAnnotation != null) {
                if (m.getParameterTypes().length != 0) {
                    throw new LumifyException("Invalid validator method " + o.getClass().getName() + "." + m.getName() + "(). Expected 0 arguments. Found " + m.getParameterTypes().length + " arguments");
                }
                if (m.getReturnType() != Boolean.TYPE) {
                    throw new LumifyException("Invalid validator method " + o.getClass().getName() + "." + m.getName() + "(). Expected Boolean return type. Found " + m.getReturnType());
                }
                validatorMap.put(m, validatorAnnotation);
            }
        }

        for (Method method : validatorMap.keySet()) {
            try {
                if (!(Boolean) method.invoke(o)) {
                    String description = validatorMap.get(method).description();
                    description = description.equals("") ? "()" : "(" + description + ")";
                    throw new LumifyException(o.getClass().getName() + "." + method.getName() + description + " returned false");
                }
            } catch (InvocationTargetException e) {
                throw new LumifyException("InvocationTargetException invoking validator " + o.getClass().getName() + "." + method.getName(), e);
            } catch (IllegalAccessException e) {
                throw new LumifyException("IllegalAccessException invoking validator " + o.getClass().getName() + "." + method.getName(), e);
            }
        }
    }

    public Map toMap() {
        return this.config;
    }

    public Iterable<String> getKeys() {
        return this.config.keySet();
    }

    public Iterable<String> getKeys(String keyPrefix) {
        getSubset(keyPrefix).keySet();
        Set<String> keys = new TreeSet<>();
        for (String key : getKeys()) {
            if (key.startsWith(keyPrefix)) {
                keys.add(key);
            }
        }
        return keys;
    }

    public void set(String propertyKey, Object value) {
        if (value == null) {
            config.remove(propertyKey);
        } else {
            config.put(propertyKey, value.toString());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        SortedSet<String> keys = new TreeSet<>(this.config.keySet());

        boolean first = true;
        for (String key : keys) {
            if (first) {
                first = false;
            } else {
                sb.append(LINE_SEPARATOR);
            }
            if (key.toLowerCase().contains("password")) {
                sb.append(key).append(": ********");
            } else {
                sb.append(key).append(": ").append(get(key, null));
            }
        }

        return sb.toString();
    }

    public org.apache.hadoop.conf.Configuration toHadoopConfiguration() {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        for (Object entryObj : this.toMap().entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            conf.set(entry.getKey().toString(), entry.getValue().toString());
        }
        return conf;
    }

    public org.apache.hadoop.conf.Configuration toHadoopConfiguration(org.apache.hadoop.conf.Configuration additionalConfiguration) {
        org.apache.hadoop.conf.Configuration hadoopConfig = toHadoopConfiguration();
        hadoopConfig.setBoolean("mapred.used.genericoptionsparser", true); // eliminates warning on our version of hadoop
        for (Map.Entry<String, String> toolConfItem : additionalConfiguration) {
            hadoopConfig.set(toolConfItem.getKey(), toolConfItem.getValue());
        }
        return hadoopConfig;
    }

    public File resolveFileName(String fileName) {
        return this.configurationLoader.resolveFileName(fileName);
    }


    public JSONObject toJSON(Locale locale) {
        if (locale == null) {
            locale = Locale.getDefault();
        }
        return toJSON(lumifyResourceBundleManager.getBundle(locale));
    }

    public JSONObject toJSON(ResourceBundle resourceBundle) {
        JSONObject properties = new JSONObject();

        OntologyRepository ontologyRepository = InjectHelper.getInstance(OntologyRepository.class);
        for (Concept concept : ontologyRepository.getConceptsWithProperties()) {
            for (String intent : concept.getIntents()) {
                properties.put(OntologyRepository.CONFIG_INTENT_CONCEPT_PREFIX + intent, concept.getIRI());
            }
        }
        for (OntologyProperty property : ontologyRepository.getProperties()) {
            for (String intent : property.getIntents()) {
                properties.put(OntologyRepository.CONFIG_INTENT_PROPERTY_PREFIX + intent, property.getTitle());
            }
        }
        for (Relationship relationship : ontologyRepository.getRelationships()) {
            for (String intent : relationship.getIntents()) {
                properties.put(OntologyRepository.CONFIG_INTENT_RELATIONSHIP_PREFIX + intent, relationship.getIRI());
            }
        }

        for (String key : getKeys()) {
            if (key.startsWith(io.lumify.core.config.Configuration.WEB_PROPERTIES_PREFIX)) {
                properties.put(key.replaceFirst(io.lumify.core.config.Configuration.WEB_PROPERTIES_PREFIX, ""), get(key, ""));
            } else if (key.startsWith("ontology.intent")) {
                properties.put(key, get(key, ""));
            }
        }

        JSONObject messages = new JSONObject();
        if (resourceBundle != null) {
            for (String key : resourceBundle.keySet()) {
                messages.put(key, resourceBundle.getString(key));
            }
        }

        JSONObject configuration = new JSONObject();
        configuration.put("properties", properties);
        configuration.put("messages", messages);

        return configuration;
    }

    public Map<String, Map<String, String>> getMultiValue(String prefix) {
        return getMultiValue(this.config.entrySet(), prefix);
    }

    /**
     * Processing configuration items that looks like this:
     * <p/>
     * repository.ontology.owl.dev.iri=http://lumify.io/dev
     * repository.ontology.owl.dev.dir=examples/ontology-dev/
     * <p/>
     * repository.ontology.owl.csv.iri=http://lumify.io/csv
     * repository.ontology.owl.csv.dir=storm/plugins/csv/ontology/
     * <p/>
     * Into a hash like this:
     * <p/>
     * - dev
     * - iri: http://lumify.io/dev
     * - dir: examples/ontology-dev/
     * - csv
     * - iri: http://lumify.io/csv
     * - dir: storm/plugins/csv/ontology/
     */
    public static Map<String, Map<String, String>> getMultiValue(Iterable<Map.Entry<String, String>> config, String prefix) {
        if (!prefix.endsWith(".")) {
            prefix = prefix + ".";
        }

        Map<String, Map<String, String>> results = new HashMap<>();
        for (Map.Entry<String, String> item : config) {
            if (item.getKey().startsWith(prefix)) {
                String rest = item.getKey().substring(prefix.length());
                int ch = rest.indexOf('.');
                String key;
                String subkey;
                if (ch > 0) {
                    key = rest.substring(0, ch);
                    subkey = rest.substring(ch + 1);
                } else {
                    key = rest;
                    subkey = "";
                }
                Map<String, String> values = results.get(key);
                if (values == null) {
                    values = new HashMap<>();
                    results.put(key, values);
                }
                values.put(subkey, item.getValue());
            }
        }
        return results;
    }
}
