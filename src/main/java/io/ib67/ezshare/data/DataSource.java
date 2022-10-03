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

package io.ib67.ezshare.data;

import io.ib67.ezshare.data.records.FileRecord;
import io.ib67.ezshare.data.records.URLRecord;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import java.util.Optional;
import java.util.function.Consumer;

public interface DataSource {
    String TABLE_FILE = "t_files";
    String TABLE_URL = "t_urls";
    void fetchFileById(String id, Consumer<Future<FileRecord>> callback);
    void fetchURLById(String id, Consumer<Future<URLRecord>> callback);

    Future<?> addFileRecord(FileRecord fr);
    Future<?> addUrlRecord(URLRecord ur);

    Future<?> removeFileRecord(FileRecord fr);
    Future<?> removeURLRecord(URLRecord ur);
}
