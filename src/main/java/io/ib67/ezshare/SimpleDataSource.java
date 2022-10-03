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

package io.ib67.ezshare;

import io.ib67.ezshare.data.DataSource;
import io.ib67.ezshare.data.records.FileRecord;
import io.ib67.ezshare.data.records.URLRecord;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class SimpleDataSource implements DataSource {
    private final Connection connection;
    private final Map<String, FileRecord> fileRecordCache = new ConcurrentHashMap<>();
    private final Map<String, URLRecord> urlRecordCache = new ConcurrentHashMap<>();

    @SneakyThrows
    @Override
    public Optional<FileRecord> fetchFileById(String id) {
        return Optional.ofNullable(fileRecordCache.computeIfAbsent(id, it -> {
            PreparedStatement stmt;
            try {
                stmt = connection.prepareStatement("SELECT * FROM files WHERE id = ?");
                stmt.setString(1, it);
                var result = stmt.executeQuery();
                return new FileRecord(result.getString("id"),
                        result.getTime("time").toInstant(),
                        Path.of(result.getString("pathToFile")),
                        result.getInt("size"),
                        result.getString("fileName"),
                        result.getString("mimeType")
                        );
            } catch (SQLException e) {
                return null;
            }
        }));
    }

    @Override
    public Optional<URLRecord> fetchURLById(String id) {
        return Optional.ofNullable(urlRecordCache.computeIfAbsent(id, it -> {
            PreparedStatement stmt;
            try {
                stmt = connection.prepareStatement("SELECT * FROM urls WHERE id = ?");
                stmt.setString(1, it);
                var result = stmt.executeQuery();
                return new URLRecord(result.getString("id"),
                        result.getTime("time").toInstant(),
                        result.getString("destination"));
            } catch (SQLException e) {
                return null;
            }
        }));
    }

    @SneakyThrows
    @Override
    public void addFileRecord(FileRecord fr) {
        fileRecordCache.put(fr.id(), fr);
        var stmt = connection.prepareStatement("INSERT INTO files VALUES (?,?,?,?,?,?)");
        stmt.setString(1, fr.id());
        stmt.setTime(2, new Time(fr.time().toEpochMilli()));
        stmt.setString(3, fr.pathToFile().toAbsolutePath().toString());
        stmt.setInt(4, fr.size());
        stmt.setString(5,fr.fileName());
        stmt.setString(6,fr.mimeType());
        stmt.executeUpdate();
    }

    @SneakyThrows
    @Override
    public void addUrlRecord(URLRecord ur) {
        urlRecordCache.put(ur.id(), ur);
        var stmt = connection.prepareStatement("INSERT INTO urls VALUES (?,?,?)");
        stmt.setString(1, ur.id());
        var time = new Time(ur.creationDate().toEpochMilli());
        stmt.setTime(2, time );
        stmt.setString(3, ur.destination());
        stmt.executeUpdate();
    }

    @Override
    public void removeFileRecord(FileRecord fr) {
        fetchFileById(fr.id()).ifPresent(it -> {
            try {
                var stmt = connection.prepareStatement("DELETE FROM files WHERE id = ?");
                stmt.setString(1, it.id());
                stmt.execute();
                fileRecordCache.remove(it.id());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void removeURLRecord(URLRecord ur) {
        fetchFileById(ur.id()).ifPresent(it -> {
            try {
                var stmt = connection.prepareStatement("DELETE FROM urls WHERE id = ?");
                stmt.setString(1, it.id());
                stmt.execute();
                urlRecordCache.remove(it.id());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
