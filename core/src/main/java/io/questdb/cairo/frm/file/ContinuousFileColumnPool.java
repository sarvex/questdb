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

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.frm.FrameColumn;
import io.questdb.cairo.frm.FrameColumnPool;
import io.questdb.cairo.frm.FrameColumnTypePool;
import io.questdb.std.FilesFacade;
import io.questdb.std.ObjList;
import io.questdb.std.str.Path;

import java.io.Closeable;
import java.io.IOException;

public class ContinuousFileColumnPool implements FrameColumnPool, Closeable {
    private final ColumnTypePool columnTypePool = new ColumnTypePool();
    private final FilesFacade ff;
    private final long fileOpts;
    private final IntPool<ContinuousFileFixFrameColumn> indexedColumnPool = new IntPool<>();
    private final long keyAppendPageSize;
    private final IntPool<ContinuousFileFixFrameColumn> fixColumnPool = new IntPool<>();
    private final long valueAppendPageSize;
    private final IntPool<ContinuousFileVarFrameColumn> varColumnPool = new IntPool<>();
    private boolean canWrite;
    private boolean isClosed;

    public ContinuousFileColumnPool(FilesFacade ff, long fileOpts, long keyAppendPageSize, long valueAppendPageSize) {
        this.ff = ff;
        this.fileOpts = fileOpts;
        this.keyAppendPageSize = keyAppendPageSize;
        this.valueAppendPageSize = valueAppendPageSize;
    }

    @Override
    public void close() throws IOException {
        this.isClosed = true;
    }

    @Override
    public FrameColumnTypePool getPoolRO(int columnType) {
        this.canWrite = false;
        return columnTypePool;
    }

    @Override
    public FrameColumnTypePool getPoolRW(int columnType) {
        this.canWrite = true;
        return columnTypePool;
    }

    private class ColumnTypePool implements FrameColumnTypePool {

        @Override
        public FrameColumn create(Path partitionPath, CharSequence columnName, long columnTxn, int columnType, int indexBlockCapacity, long columnTop, int columnIndex) {
            boolean isIndexed = indexBlockCapacity > 0;
            switch (columnType) {
                case ColumnType.SYMBOL:
                    if (canWrite && isIndexed) {
                        var indexedColumn = getIndexedColumn();
                        indexedColumn.ofRW(partitionPath, columnName, columnTxn, columnType, indexBlockCapacity, columnTop, columnIndex);
                        return indexedColumn;
                    }

                default: {
                    var column = getFixColumn();
                    if (canWrite) {
                        column.ofRW(partitionPath, columnName, columnTxn, columnType, columnTop, columnIndex);
                    } else {
                        column.ofRO(partitionPath, columnName, columnTxn, columnType, columnTop, columnIndex);
                    }
                    return column;
                }

                case ColumnType.STRING:
                case ColumnType.BINARY: {
                    var column = getVarColumn();
                    if (canWrite) {
                        column.ofRW(partitionPath, columnName, columnTxn, columnType, columnTop, columnIndex);
                    } else {
                        column.ofRO(partitionPath, columnName, columnTxn, columnType, columnTop, columnIndex);
                    }
                    return column;
                }
            }
        }

        private ContinuousFileFixFrameColumn getFixColumn() {
            if (fixColumnPool.size() > 0) {
                var col = fixColumnPool.getLast();
                fixColumnPool.setPos(fixColumnPool.size() - 1);
                return col;
            }
            var col = new ContinuousFileFixFrameColumn(ff, fileOpts);
            col.setPool(fixColumnPool);
            return col;
        }

        private ContinuousFileIndexedFrameColumn getIndexedColumn() {
            if (indexedColumnPool.size() > 0) {
                var col = indexedColumnPool.getLast();
                indexedColumnPool.setPos(indexedColumnPool.size() - 1);
                return (ContinuousFileIndexedFrameColumn) col;
            }
            var col = new ContinuousFileIndexedFrameColumn(ff, fileOpts, keyAppendPageSize, valueAppendPageSize);
            col.setPool(indexedColumnPool);
            return col;
        }

        private ContinuousFileVarFrameColumn getVarColumn() {
            if (varColumnPool.size() > 0) {
                var col = varColumnPool.getLast();
                varColumnPool.setPos(varColumnPool.size() - 1);
                return col;
            }
            var col = new ContinuousFileVarFrameColumn(ff, fileOpts);
            col.setPool(varColumnPool);
            return col;
        }
    }

    private class IntPool<T> implements Pool<T> {
        private final ObjList<T> pool = new ObjList<>();

        public void add(T t) {
            pool.add(t);
        }

        public T getLast() {
            return pool.getLast();
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }

        @Override
        public void put(T frame) {
            pool.add(frame);
        }

        public void setPos(int pos) {
            pool.setPos(pos);
        }

        public int size() {
            return pool.size();
        }
    }
}
