package brooklyn.networking.vclouddirector.natmicroservice;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.api.client.util.Lists;
import com.google.common.collect.Maps;

import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.stream.Streams;

import brooklyn.networking.vclouddirector.NatServiceDispatcher.EndpointConfig;

public class PropertiesParser {

    public static final String ENDPOINT_SUFFIX = ".endpoint";
    public static final String PORT_RANGE_SUFFIX = ".portRange";
    public static final String TURST_STORE_SUFFIX = ".trustStore";
    public static final String TURST_STORE_PASSWORD_SUFFIX = ".trustStorePassword";

    public static Map<String, EndpointConfig> parseProperties(String file) {
        return parseProperties(loadProperties(file));
    }

    public static Map<String, EndpointConfig> parseProperties(InputStream in) {
        return parseProperties(loadProperties(in));
    }
    
    public static Map<String, EndpointConfig> parseProperties(Properties props) {
        Map<String, EndpointConfig> result = Maps.newLinkedHashMap();
        List<String> namePrefixes = Lists.newArrayList();
        for (Enumeration<?> names = props.propertyNames(); names.hasMoreElements();) {
            String name = (String) names.nextElement();
            props.getProperty(name);
            if (name.endsWith(ENDPOINT_SUFFIX)) {
                namePrefixes.add(name.substring(0, name.length()-ENDPOINT_SUFFIX.length()));
            }
        }
        for (Object namePrefix : namePrefixes) {
            String endpoint = props.getProperty(namePrefix + ENDPOINT_SUFFIX);
            String portRangeStr = props.getProperty(namePrefix + PORT_RANGE_SUFFIX);
            PortRange portRange = (portRangeStr == null) ? null : PortRanges.fromString(portRangeStr);
            String trustStore = props.getProperty(namePrefix + TURST_STORE_SUFFIX);
            String trustStorePassword = props.getProperty(namePrefix + TURST_STORE_PASSWORD_SUFFIX);
            result.put(endpoint, new EndpointConfig(portRange, trustStore, trustStorePassword));
        }
        return result;
    }
    
    static Properties loadProperties(String file) {
        File f = new File(Os.tidyPath(file));
        checkArgument(f.exists(), "file %s does not exist", f);
        checkArgument(f.isFile(), "file %s is not a file", f);

        FileInputStream stream = null;
        try {
            stream = new FileInputStream(f);
            return loadProperties(stream);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        } finally {
            if (stream != null) Streams.closeQuietly(stream);
        }
    }
    
    static Properties loadProperties(InputStream stream) {
        Properties props = new Properties();
        try {
            props.load(stream);
            return props;
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
}
