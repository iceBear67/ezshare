/*
 *
 * MIT License
 *
 * Copyright (c) 2022 iceBear67 and Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.ib67.ezshare.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigBeanFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record AppConfig(
        String addr,
        int port,
        Path uploadContainer,
        String jdbcUrl,
        String baseUrl,
        int maxFileSizeKiB,
        int preservedSize,
        List<String> bannedTypes
) {

    public static AppConfig loadConfig(Config config) throws IOException {
        var burl = config.getString("base-url");
        return new AppConfig(
                config.getString("listen-addr"),
                config.getInt("listen-port"),
                Path.of(config.getString("upload-dir")),
                config.getString("jdbc-url"),
                burl.endsWith("/") ? burl.substring(0, burl.length() - 1) : burl,
                config.getInt("max-allowed-size-inkib"),
                config.getInt("preserve-space"),
                config.getStringList("banned-content-types")
        );
    }

}
