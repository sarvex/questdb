/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.frm.file;

import io.questdb.cairo.CairoException;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.frm.FrameColumn;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.FilesFacade;
import io.questdb.std.MemoryTag;
import io.questdb.std.str.Path;

import static io.questdb.cairo.TableUtils.dFile;

public class ContinuousFileFixFrameColumn implements FrameColumn {
    public static final int MEMORY_TAG = MemoryTag.MMAP_TABLE_WRITER;
    private static final Log LOG = LogFactory.getLog(ContinuousFileFixFrameColumn.class);
    private final FilesFacade ff;
    private final long fileOpts;
    private int columnIndex;
    private long columnTop;
    private int columnType;
    private int fd = -1;
    private Pool<ContinuousFileFixFrameColumn> pool;
    private int shl;

    public ContinuousFileFixFrameColumn(FilesFacade ff, long fileOpts) {
        this.ff = ff;
        this.fileOpts = fileOpts;
    }

    @Override
    public void append(long offset, FrameColumn sourceColumn, long sourceOffset, long count) {
        if (sourceColumn.getStorageType() == COLUMN_CONTINUOUS_FILE) {
            sourceOffset -= sourceColumn.getColumnTop();
            count -= sourceColumn.getColumnTop();
            offset -= columnTop;

            assert sourceOffset >= 0;
            assert count >= 0;
            assert offset >= 0;

            if (count > 0) {
                int sourceFd = sourceColumn.getPrimaryFd();
                long length = count << shl;
                if (!ff.truncate(fd, (offset + count) << shl)) {
                    throw CairoException.critical(ff.errno()).put("Cannot set file size [fd=").put(fd).put(", size=").put((offset + count) << shl).put(']');
                }
                if (ff.copyData(sourceFd, fd, sourceOffset << shl, offset << shl, length) != length) {
                    throw CairoException.critical(ff.errno()).put("Cannot copy data [fd=").put(fd)
                            .put(", destOffset=").put(offset << shl)
                            .put(", size=").put(length)
                            .put(", fileSize").put(ff.length(fd))
                            .put(", srcFd=").put(sourceColumn.getSecondaryFd())
                            .put(", srcOffset=").put(sourceOffset << shl)
                            .put(", srcFileSize=").put(ff.length(sourceColumn.getSecondaryFd()))
                            .put(']');
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void appendNulls(long offset, long count) {
        offset -= columnTop;
        assert offset >= 0;
        assert count >= 0;

        if (count > 0) {
            if (!ff.truncate(fd, (offset + count) << shl)) {
                throw CairoException.critical(ff.errno()).put("Cannot set file size to pad with nulls [fd=").put(fd).put(", size=").put((offset + count) << shl).put(']');
            }
            long mappedAddress = TableUtils.mapAppendColumnBuffer(ff, fd, offset << shl, count << shl, true, MEMORY_TAG);
            try {
                TableUtils.setNull(columnType, mappedAddress, count);
            } finally {
                TableUtils.mapAppendColumnBufferRelease(ff, mappedAddress, offset << shl, count << shl, MEMORY_TAG);
            }
        }
    }

    @Override
    public void close() {
        if (fd > -1) {
            ff.close(fd);
            fd = -1;
        }
        if (!pool.isClosed()) {
            pool.put(this);
        }
    }

    @Override
    public int getColumnIndex() {
        return columnIndex;
    }

    @Override
    public long getColumnTop() {
        return columnTop;
    }

    @Override
    public int getColumnType() {
        return columnType;
    }

    @Override
    public long getPrimaryAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPrimaryFd() {
        return fd;
    }

    @Override
    public long getSecondaryAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSecondaryFd() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getStorageType() {
        return COLUMN_CONTINUOUS_FILE;
    }

    public void ofRO(Path partitionPath, CharSequence columnName, long columnTxn, int columnType, long columnTop, int columnIndex) {
        assert fd == -1;
        of(columnType, columnTop, columnIndex);

        if (columnTop >= 0) {
            int plen = partitionPath.length();
            try {
                dFile(partitionPath, columnName, columnTxn);
                this.fd = TableUtils.openRO(ff, partitionPath.$(), LOG);
            } finally {
                partitionPath.trimTo(plen);
            }
        } else {
            // Column does not exist in the partition, don't try to open the file
            this.columnTop = -columnTop;
        }
    }

    public void ofRW(Path partitionPath, CharSequence columnName, long columnTxn, int columnType, long columnTop, int columnIndex) {
        assert fd == -1;
        // Negative col top means column does not exist in the partition.
        // Create it.
        columnTop = Math.abs(columnTop);
        of(columnType, columnTop, columnIndex);

        int plen = partitionPath.length();
        try {
            dFile(partitionPath, columnName, columnTxn);
            this.fd = TableUtils.openRW(ff, partitionPath.$(), LOG, fileOpts);
        } finally {
            partitionPath.trimTo(plen);
        }
    }

    @Override
    public void setAddTop(long value) {
        assert value >= 0;
        columnTop += value;
    }

    public void setPool(Pool<ContinuousFileFixFrameColumn> pool) {
        assert this.pool == null;
        this.pool = pool;
    }

    private void of(int columnType, long columnTop, int columnIndex) {
        this.shl = ColumnType.pow2SizeOf(columnType);
        this.columnType = columnType;
        this.columnTop = columnTop;
        this.columnIndex = columnIndex;
    }
}
