package io.lumify.core.version;

import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import com.google.inject.Singleton;

import javax.management.*;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Properties;

/**
 * This implementation of the LumifyVersionService loads its configuration
 * from classpath://lumify-build.properties.  If the file does not exist,
 * all methods will return <code>null</code>.
 */
@Singleton
public class VersionService implements VersionServiceMXBean {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(VersionService.class);
    public static String JMX_NAME = "io.lumify:type=" + VersionService.class.getName();

    /**
     * The name of the properties file to read.
     */
    private static final String LUMIFY_BUILD_PROPERTIES = "META-INF/lumify/lumify-core-build.properties";

    /**
     * The Lumify version property.
     */
    private static final String LUMIFY_VERSION_PROPERTY = "project.version";

    /**
     * The SCM build number property.
     */
    private static final String SCM_BUILD_NUMBER_PROPERTY = "project.scm.revision";

    /**
     * The build timestamp property.
     */
    private static final String BUILD_TIME_PROPERTY = "build.timestamp";

    /**
     * The Lumify version.
     */
    private final String version;

    /**
     * The SCM build number.
     */
    private final String scmBuildNumber;

    /**
     * The build timestamp.
     */
    private final Long unixBuildTime;

    public VersionService() {
        String version = "Unavailable";
        String scmBuildNumber = "Unavailable";
        Long unixBuildTime = 0L;
        try {
            Properties props = new Properties();
            InputStream is = this.getClass().getClassLoader().getResourceAsStream(LUMIFY_BUILD_PROPERTIES);
            if (is == null) {
                LOGGER.warn("Property file [%s] not found in the classpath. Version information will be unavailable.", LUMIFY_BUILD_PROPERTIES);
            } else {
                props.load(is);
                version = props.getProperty(LUMIFY_VERSION_PROPERTY);
                scmBuildNumber = props.getProperty(SCM_BUILD_NUMBER_PROPERTY);
                String strTime = props.getProperty(BUILD_TIME_PROPERTY);
                if (strTime != null) {
                    try {
                        unixBuildTime = Long.parseLong(strTime);
                    } catch (NumberFormatException nfe) {
                        LOGGER.warn("Invalid build timestamp [%s].", strTime);
                    }
                }
            }
        } catch (IOException ioe) {
            LOGGER.error("Unable to read Lumify version properties. Version information will be unavailable.", ioe);
        }
        this.version = version;
        this.scmBuildNumber = scmBuildNumber;
        this.unixBuildTime = unixBuildTime;

        try {
            registerJmxBean();
        } catch (Exception ex) {
            LOGGER.error("Could not register JMX bean", ex);
        }
    }

    private void registerJmxBean() throws MalformedObjectNameException, NotCompliantMBeanException, MBeanRegistrationException {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName mxbeanName = new ObjectName(JMX_NAME);
        try {
            mbs.registerMBean(this, mxbeanName);
        } catch (InstanceAlreadyExistsException ex) {
            // ignore
        }
    }

    @Override
    public Long getUnixBuildTime() {
        return unixBuildTime;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getScmBuildNumber() {
        return scmBuildNumber;
    }
}
