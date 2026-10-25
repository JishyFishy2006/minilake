package minilake.parquet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParquetRoundTripTest {

    record Row(long id, String name, double score) {}

    static final MessageType SCHEMA = MessageTypeParser.parseMessageType("""
        message row {
          required int64 id;
          required binary name (STRING);
          required double score;
        }
        """);

    @Test
    void writeThenReadThreeColumns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.parquet");
        List<Row> expected = List.of(
            new Row(1, "alice", 9.5),
            new Row(2, "bob", 7.25),
            new Row(3, "carol", 8.0));

        // ---- write ----
        SimpleGroupFactory groups = new SimpleGroupFactory(SCHEMA);
        try (ParquetWriter<Group> writer = ExampleParquetWriter
                .builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())   // non-Hadoop config
                .withType(SCHEMA)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Row r : expected) {
                writer.write(groups.newGroup()
                    .append("id", r.id())
                    .append("name", r.name())
                    .append("score", r.score()));
            }
        }

        // ---- read ----
        List<Row> actual = new ArrayList<>();
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file))) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO(schema);
            PageReadStore rowGroup;
            while ((rowGroup = reader.readNextRowGroup()) != null) {
                RecordReader<Group> records =
                    columnIO.getRecordReader(rowGroup, new GroupRecordConverter(schema));
                for (long i = 0; i < rowGroup.getRowCount(); i++) {
                    Group g = records.read();
                    actual.add(new Row(
                        g.getLong("id", 0),
                        g.getString("name", 0),
                        g.getDouble("score", 0)));
                }
            }
        }

        assertEquals(expected, actual);
    }
}