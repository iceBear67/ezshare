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

import io.ib67.ezshare.config.AppConfig;
import io.ib67.ezshare.data.records.FileRecord;
import io.ib67.ezshare.data.records.URLRecord;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.PrepareOptions;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class SimpleDataSource implements DataSource {
    private final JDBCPool pool;
    private final AppConfig config;

    private static final String SQL_QUERY_FILE_BY_ID = "SELECT * FROM " + TABLE_FILE + " WHERE id = ?";
    private static final String SQL_QUERY_URL_BY_ID = "SELECT * FROM " + TABLE_URL + " WHERE id = ?";

    private static final String SQL_INSERT_FILE = "INSERT INTO " + TABLE_FILE + " VALUES (?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_URL = "INSERT INTO " + TABLE_URL + " VALUES (?,?,?,?)";

    private static final String SQL_DELETE_URL_BY_ID = "DELETE FROM " + TABLE_URL + " WHERE id = ?";
    private static final String SQL_DELETE_FILE_BY_ID = "DELETE FROM " + TABLE_FILE + " WHERE id = ?";

    @Override
    public void fetchFileById(String id, Consumer<Future<FileRecord>> callback) {
        pool.preparedQuery(SQL_QUERY_FILE_BY_ID)
                .execute(Tuple.of(id))
                .onFailure(t -> {
                    log.error("fetchFileById: {}",t.getMessage());
                    callback.accept(Future.failedFuture(t));
                }).onSuccess(rows -> {
                    if (rows.size() == 0) {
                        callback.accept(Future.failedFuture("Cannot find a file with this id"));
                        return;
                    }
                    var result = rows.iterator().next(); // id is unique.
                    callback.accept(Future.succeededFuture(new FileRecord(
                            result.getString(0),
                            result.getLocalDateTime(1),
                            result.getString(2),
                            result.getLong(3),
                            result.getString(4),
                            result.getString(5),
                            result.getString(6),
                            result.getString(7)
                    )));
                });
    }

    @Override
    public void fetchURLById(String id, Consumer<Future<URLRecord>> callback) {
        pool.preparedQuery(SQL_QUERY_URL_BY_ID)
                .execute(Tuple.of(id))
                .onFailure(t -> {
                    log.error("fetchURLById: {}",t.getMessage());
                    callback.accept(Future.failedFuture(t));
                }).onSuccess(rows -> {
                    if (rows.size() == 0) {
                        callback.accept(Future.failedFuture("Cannot find a url with this id"));
                        return;
                    }
                    var result = rows.iterator().next(); // id is unique.
                    callback.accept(Future.succeededFuture(new URLRecord(
                            result.getString(0),
                            result.getLocalDateTime(1),
                            result.getString(2),
                            result.getString(3)
                    )));
                });
    }

    @Override
    public Future<?> addFileRecord(FileRecord fr) {
        return pool.preparedQuery(SQL_INSERT_FILE)
                .execute(Tuple.of(
                        fr.id(),
                        fr.time(),
                        fr.fileIdentifier(),
                        fr.size(),
                        fr.fileName(),
                        fr.mimeType(),
                        fr.ip(),
                        fr.storageType()
                )).onFailure(t->log.error("addFileRecord: {}",t.getMessage()));
    }

    @Override
    public Future<?> addUrlRecord(URLRecord ur) {
        return pool.preparedQuery(SQL_INSERT_URL)
                .execute(Tuple.of(
                        ur.id(),
                        ur.time(),
                        ur.destination(),
                        ur.ip()
                )).onFailure(t->log.error("addUrlRecord: {}",t.getMessage()));
    }

    @Override
    public Future<?> removeFileRecord(FileRecord fr) {
        return pool.preparedQuery(SQL_DELETE_FILE_BY_ID).execute(Tuple.of(fr.id())).onFailure(t->log.error("removeFileRecord: {}",t.getMessage()));
    }

    @Override
    public Future<?> removeURLRecord(URLRecord ur) {
        return pool.preparedQuery(SQL_DELETE_URL_BY_ID)
                .execute(Tuple.of(ur.id()))
                .onFailure(t->log.error("removeUrlRecord: {}",t.getMessage()));
    }
}
