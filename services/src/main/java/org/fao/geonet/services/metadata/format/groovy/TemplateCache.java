package org.fao.geonet.services.metadata.format.groovy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.Files;
import org.fao.geonet.Constants;
import org.fao.geonet.SystemInfo;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.services.metadata.format.ConfigFile;
import org.fao.geonet.services.metadata.format.FormatterConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import javax.annotation.PostConstruct;

/**
 * A Cache for the template files that are loaded by FileResult objects.  This is intended to reduce the number of files that
 * need to be loaded.
 *
 * @author Jesse on 10/20/2014.
 */
@Component
public class TemplateCache {
    @VisibleForTesting
    @Autowired
    SystemInfo systemInfo;
    @VisibleForTesting
    @Autowired
    SchemaManager schemaManager;


    private int maxSizeKB = 100000;
    Cache<String, String> canonicalFileNameToText;
    private int concurrencyLevel = 4;

    @PostConstruct
    void init() {
        long maxSize = ((long) maxSizeKB * 1024) / 8;
        if (maxSize > Integer.MAX_VALUE) {
            final long maxAllowed = ((long) Integer.MAX_VALUE * 8) / 1024;
            throw new AssertionError("maxSizeKB is too large: " + maxSizeKB + " max allowed value is: " + maxAllowed);
        }
        if (maxSize < 1) {
            throw new AssertionError("maxSizeKB is too small: " + maxSizeKB);
        }
        canonicalFileNameToText = CacheBuilder.newBuilder().
                concurrencyLevel(this.concurrencyLevel).
                initialCapacity(100).
                maximumWeight((int) maxSize). // allow caching roughly 100MB maximum
                weigher(new StringLengthWeigher()).
                build();
    }

    private class StringLengthWeigher implements com.google.common.cache.Weigher<String, String> {
        @Override
        public int weigh(String key, String value) {
            return key.length() + value.length();
        }
    }

    public void setMaxSizeKB(int maxSizeKB) {
        this.maxSizeKB = maxSizeKB;
    }

    public void setConcurrencyLevel(int concurrencyLevel) {
        this.concurrencyLevel = concurrencyLevel;
    }

    synchronized FileResult createFileResult(File formatterDir, File schemaDir, File rootFormatterDir, String path,
                                             Map<String, Object> substitutions) throws IOException {
        File file = new File(formatterDir, path);
        String template = this.canonicalFileNameToText.getIfPresent(file.getCanonicalPath());
        File fromParentSchema = null;

        if (!this.systemInfo.isDevMode()) {
            if (template != null) {
                return new FileResult(file, template, substitutions);
            }

            file = new File(schemaDir, path);
            template = this.canonicalFileNameToText.getIfPresent(file.getCanonicalPath());
            if (template != null) {
                return new FileResult(file, template, substitutions);
            }
            fromParentSchema = fromParentSchema(formatterDir, schemaDir, path);
            if (fromParentSchema != null) {
                template = this.canonicalFileNameToText.getIfPresent(fromParentSchema.getCanonicalPath());
                if (template != null) {
                    return new FileResult(fromParentSchema, template, substitutions);
                }
            }

            file = new File(rootFormatterDir, path);
            template = this.canonicalFileNameToText.getIfPresent(file.getCanonicalPath());
            if (template != null) {
                return new FileResult(file, template, substitutions);
            }
        }

        file = new File(formatterDir, path);
        if (!file.exists() && schemaDir != null) {
            file = new File(schemaDir, path);
        }

        if (!file.exists()) {
            if (fromParentSchema == null) {
                fromParentSchema = fromParentSchema(formatterDir, schemaDir, path);
            }
            if (fromParentSchema != null) {
                file = fromParentSchema;
            }
        }
        if (!file.exists()) {
            file = new File(rootFormatterDir, path);
        }
        if (!file.exists()) {
            throw new IllegalArgumentException("There is no file: " + path + " in any of: \n" +
                                               "\t * " + formatterDir + "\n" +
                                               "\t * " + schemaDir + "\n" +
                                               "\t * if parent exists: " + fromParentSchema + "\n" +
                                               "\t * " + rootFormatterDir);
        }

        template = Files.toString(file, Constants.CHARSET);
        this.canonicalFileNameToText.put(file.getCanonicalPath(), template);

        return new FileResult(file, template, substitutions);
    }

    private File fromParentSchema(File formatterDir, File schemaDir, String path) throws IOException {
        final ConfigFile configFile;
        if (formatterDir != null) {
            configFile = new ConfigFile(formatterDir, true, schemaDir);
        } else {
            configFile = new ConfigFile(schemaDir, false, null);
        }

        final String schemaName = configFile.dependOn();
        if (schemaName != null) {
            File parentSchema = new File(this.schemaManager.getSchemaDir(schemaName), FormatterConstants.SCHEMA_PLUGIN_FORMATTER_DIR);

            File file = new File(parentSchema, path);
            if (file.exists()) {
                return file;
            }

            return fromParentSchema(null, parentSchema, path);
        }


        return null;
    }
}
