package com.rookie.submit.cust.source.socket;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.connector.format.DecodingFormat;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DeserializationFormatFactory;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.types.DataType;

import java.util.HashSet;
import java.util.Set;

public class SocketDynamicTableFactory implements DynamicTableSourceFactory {

  // define all options statically
  public static final ConfigOption<String> HOSTNAME = ConfigOptions.key("hostname")
    .stringType()
    .noDefaultValue();

  public static final ConfigOption<Integer> PORT = ConfigOptions.key("port")
    .intType()
    .noDefaultValue();

  public static final ConfigOption<Integer> BYTE_DELIMITER = ConfigOptions.key("byte-delimiter")
    .intType()
    .defaultValue(10); // corresponds to '\n'

  @Override
  public String factoryIdentifier() {
    return "socket"; // used for matching to `connector = '...'`
  }

  @Override
  public Set<ConfigOption<?>> requiredOptions() {
    final Set<ConfigOption<?>> options = new HashSet<>();
    options.add(HOSTNAME);
    options.add(PORT);
    options.add(FactoryUtil.FORMAT); // use pre-defined option for format
    return options;
  }

  @Override
  public Set<ConfigOption<?>> optionalOptions() {
    final Set<ConfigOption<?>> options = new HashSet<>();
    options.add(BYTE_DELIMITER);
    return options;
  }

  @Override
  public DynamicTableSource createDynamicTableSource(Context context) {
    // either implement your custom validation logic here ...
    // or use the provided helper utility
    final FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);

    // discover a suitable decoding format
    final DecodingFormat<DeserializationSchema<RowData>> decodingFormat = helper.discoverDecodingFormat(
      DeserializationFormatFactory.class,
      FactoryUtil.FORMAT);

    // validate all options
    helper.validate();

    // get the validated options
    final ReadableConfig options = helper.getOptions();
    final String hostname = options.get(HOSTNAME);
    final int port = options.get(PORT);
    final byte byteDelimiter = (byte) (int) options.get(BYTE_DELIMITER);

    // derive the produced data type (excluding computed columns) from the catalog table
    final DataType producedDataType =
            context.getCatalogTable().getResolvedSchema().toPhysicalRowDataType();

    // create and return dynamic table source
    return new SocketDynamicTableSource(hostname, port, byteDelimiter, decodingFormat, producedDataType);
  }
}