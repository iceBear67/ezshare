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

package io.ib67.ezshare.storage;

import io.ib67.ezshare.data.records.FileRecord;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface IStorageProvider {
    void store(RoutingContext context, FileUpload file, Consumer<Future<String>> identifierCallback);
    void download(FileRecord fr, RoutingContext context);

    void delete(FileRecord fr);
}
