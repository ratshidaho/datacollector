/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.origin.spooldir;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.RawSource;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.api.base.FileRawSourcePreviewer;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.CsvModeChooserValues;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.dirspooler.DirectorySpooler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@GenerateResourceBundle
@StageDef(
    version = "1.0.0",
    label = "Directory",
    description = "Reads files from a directory",
    icon="spoolDirSource.png"
)
@RawSource(rawSourcePreviewer = FileRawSourcePreviewer.class)
@ConfigGroups(com.streamsets.pipeline.stage.origin.spooldir.ConfigGroups.class)
public class SpoolDirSource extends BaseSource {
  private final static Logger LOG = LoggerFactory.getLogger(SpoolDirSource.class);
  private static final String OFFSET_SEPARATOR = "::";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Data Format",
      description = "Format of data in the files",
      displayPosition = 0,
      group = "FILES"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = DataFormatChooserValues.class)
  public DataFormat dataFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Files Directory",
      description = "Use a local directory",
      displayPosition = 10,
      group = "FILES"
  )
  public String spoolDir;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.INTEGER,
      label = "Batch Size (recs)",
      defaultValue = "1000",
      description = "Max number of records per batch",
      displayPosition = 20,
      group = "FILES"
  )
  public int batchSize;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.INTEGER,
      defaultValue = "600",
      label = "Batch Wait Time (secs)",
      description = "Max time to wait for new files before sending an empty batch",
      displayPosition = 30,
      group = "FILES"
  )
  public long poolingTimeoutSecs;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "File Name Pattern",
      description = "A glob or regular expression that defines the pattern of the file names in the directory. " +
                    "Files are processed in naturally ascending order.",
      displayPosition = 40,
      group = "FILES",
      dependsOn = "dataFormat",
      triggeredByValue = { "TEXT", "JSON", "XML", "DELIMITED"}
  )
  public String filePattern;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.INTEGER,
      defaultValue = "10",
      label = "Max Files in Directory",
      description = "Max number of files in the directory waiting to be processed. Additional files cause the " +
                    "pipeline to fail.",
      displayPosition = 60,
      group = "FILES",
      dependsOn = "dataFormat",
      triggeredByValue = { "TEXT", "JSON", "XML", "DELIMITED"}
  )
  public int maxSpoolFiles;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "First File to Process",
      description = "When configured, the Data Collector does not process earlier (naturally ascending order) file names",
      displayPosition = 50,
      group = "FILES",
      dependsOn = "dataFormat",
      triggeredByValue = { "TEXT", "JSON", "XML", "DELIMITED"}
  )
  public String initialFileToProcess;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "Error Directory",
      description = "Directory for files that could not be fully processed",
      displayPosition = 100,
      group = "POST_PROCESSING"
  )
  public String errorArchiveDir;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "NONE",
      label = "File Post Processing",
      description = "Action to take after processing a file",
      displayPosition = 110,
      group = "POST_PROCESSING"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = PostProcessingOptionsChooserValues.class)
  public PostProcessingOptions postProcessing;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "Archiving Directory",
      description = "Directory to archive files after they have been processed",
      displayPosition = 200,
      group = "POST_PROCESSING",
      dependsOn = "postProcessing",
      triggeredByValue = "ARCHIVE"
  )
  public String archiveDir;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.INTEGER,
      defaultValue = "0",
      label = "Archive Retention Time (minutes)",
      description = "How long archived files should be kept before deleting, a value of zero means forever",
      displayPosition = 210,
      group = "POST_PROCESSING",
      dependsOn = "postProcessing",
      triggeredByValue = "ARCHIVE"
  )
  public long retentionTimeMins;

  // CSV Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "CSV",
      label = "File Type",
      description = "",
      displayPosition = 300,
      group = "DELIMITED",
      dependsOn = "dataFormat",
      triggeredByValue = "DELIMITED"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = CsvModeChooserValues.class)
  public CsvMode csvFileFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "TRUE",
      label = "Header Line",
      description = "",
      displayPosition = 310,
      group = "DELIMITED",
      dependsOn = "dataFormat",
      triggeredByValue = "DELIMITED"
  )
  public boolean hasHeaderLine;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "TRUE",
      label = "Convert to Map",
      description = "Converts delimited values to a map based on the header or placeholder header values",
      displayPosition = 320,
      group = "DELIMITED",
      dependsOn = "dataFormat",
      triggeredByValue = "DELIMITED"
  )
  public boolean convertToMap;

  // JSON Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "MULTIPLE_OBJECTS",
      label = "JSON Content",
      description = "",
      displayPosition = 400,
      group = "JSON",
      dependsOn = "dataFormat",
      triggeredByValue = "JSON"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = JsonFileModeChooserValues.class)
  public JsonFileMode jsonContent;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.INTEGER,
      defaultValue = "4096",
      label = "Max Object Length (chars)",
      description = "Larger objects are not processed",
      displayPosition = 410,
      group = "JSON",
      dependsOn = "dataFormat",
      triggeredByValue = "JSON"
  )
  public int maxJsonObjectLen;

  // LOG Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.INTEGER,
      defaultValue = "1024",
      label = "Max Line Length",
      description = "Longer lines are truncated",
      displayPosition = 500,
      group = "TEXT",
      dependsOn = "dataFormat",
      triggeredByValue = "TEXT"
  )
  public int maxLogLineLength;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Add Truncated Flag",
      description = "Adds a boolean /truncated field to the record to indicate if the record has been truncated",
      displayPosition = 510,
      group = "TEXT",
      dependsOn = "dataFormat",
      triggeredByValue = "TEXT"
  )

  public boolean setTruncated;

  // XML Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Delimiter Element",
      description = "XML element that acts as a record delimiter",
      displayPosition = 600,
      group = "XML",
      dependsOn = "dataFormat",
      triggeredByValue = "XML"
  )
  public String xmlRecordElement;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.INTEGER,
      defaultValue = "4096",
      label = "Max Record Length (chars)",
      description = "Larger records are not processed",
      displayPosition = 610,
      group = "XML",
      dependsOn = "dataFormat",
      triggeredByValue = "XML"
  )
  public int maxXmlObjectLen;

  private DirectorySpooler spooler;
  private File currentFile;
  private DataProducer dataProducer;

  @Override
  protected void init() throws StageException {
    super.init();

    switch (dataFormat) {
      case TEXT:
        dataProducer = new LogDataProducer(getContext(), maxLogLineLength, setTruncated);
        break;
      case JSON:
        dataProducer = new JsonDataProducer(getContext(), jsonContent, maxJsonObjectLen);
        break;
      case DELIMITED:
        dataProducer = new CsvDataProducer(getContext(), csvFileFormat, hasHeaderLine, convertToMap);
        break;
      case XML:
        dataProducer = new XmlDataProducer(getContext(), xmlRecordElement, maxXmlObjectLen);
        break;
      case SDC_JSON:
        filePattern = "records-??????.json";
        initialFileToProcess = "";
        maxSpoolFiles = 10000;
        dataProducer = new RecordJsonDataProducer(getContext());
        break;
    }

    if (getContext().isPreview()) {
      poolingTimeoutSecs = 1;
    }

    DirectorySpooler.Builder builder = DirectorySpooler.builder().setDir(spoolDir).setFilePattern(filePattern).
        setMaxSpoolFiles(maxSpoolFiles).setPostProcessing(postProcessing.getSpoolerAction());
    if (postProcessing == PostProcessingOptions.ARCHIVE) {
      builder.setArchiveDir(archiveDir);
      builder.setArchiveRetention(retentionTimeMins);
    }
    if (errorArchiveDir != null && !errorArchiveDir.isEmpty()) {
      builder.setErrorArchiveDir(errorArchiveDir);
    }
    builder.setContext(getContext());
    spooler = builder.build();
    spooler.init(initialFileToProcess);
  }


  @Override
  public void destroy() {
    spooler.destroy();
    super.destroy();
  }

  protected DirectorySpooler getSpooler() {
    return spooler;
  }

  protected String getFileFromSourceOffset(String sourceOffset) throws StageException {
    String file = null;
    if (sourceOffset != null) {
      file = sourceOffset;
      int separator = sourceOffset.indexOf(OFFSET_SEPARATOR);
      if (separator > -1) {
        file = sourceOffset.substring(0, separator);
      }
    }
    return file;
  }

  protected long getOffsetFromSourceOffset(String sourceOffset) throws StageException {
    long offset = 0;
    if (sourceOffset != null) {
      int separator = sourceOffset.indexOf(OFFSET_SEPARATOR);
      if (separator > -1) {
        offset = Long.parseLong(sourceOffset.substring(separator + OFFSET_SEPARATOR.length()));
      }
    }
    return offset;
  }

  protected String createSourceOffset(String file, long fileOffset) {
    return (file != null) ? file + OFFSET_SEPARATOR + Long.toString(fileOffset) : null;
  }

  private boolean hasToFetchNextFileFromSpooler(String file, long offset) {
    return
        // we don't have a current file half way processed in the current agent execution
        currentFile == null ||
        // we don't have a file half way processed from a previous agent execution via offset tracking
        file == null ||
        // the current file is lexicographically lesser than the one reported via offset tracking
        // this can happen if somebody drop
        currentFile.getName().compareTo(file) < 0 ||
        // the current file has been fully processed
        offset == -1;
  }

  private boolean isFileFromSpoolerEligible(File spoolerFile, String offsetFile, long offsetInFile) {
    if (spoolerFile == null) {
      // there is no new file from spooler, we return yes to break the loop
      return true;
    }
    if (offsetFile == null) {
      // file reported by offset tracking is NULL, means we are starting from zero
      return true;
    }
    if (spoolerFile.getName().compareTo(offsetFile) == 0 && offsetInFile != -1) {
      // file reported by spooler is equal than current offset file
      // and we didn't fully process (not -1) the current file
      return true;
    }
    if (spoolerFile.getName().compareTo(offsetFile) > 0) {
      // file reported by spooler is newer than current offset file
      return true;
    }
    return false;
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    int batchSize = Math.min(this.batchSize, maxBatchSize);
    // if lastSourceOffset is NULL (beginning of source) it returns NULL
    String file = getFileFromSourceOffset(lastSourceOffset);
    // if lastSourceOffset is NULL (beginning of source) it returns 0
    long offset = getOffsetFromSourceOffset(lastSourceOffset);
    if (hasToFetchNextFileFromSpooler(file, offset)) {
      currentFile = null;
      try {
        File nextAvailFile = null;
        do {
          if (nextAvailFile != null) {
            LOG.warn("Ignoring file '{}' in spool directory as is lesser than offset file '{}'",
                     nextAvailFile.getName(), file);
          }
          nextAvailFile = getSpooler().poolForFile(poolingTimeoutSecs, TimeUnit.SECONDS);
        } while (!isFileFromSpoolerEligible(nextAvailFile, file, offset));

        if (nextAvailFile == null) {
          // no file to process
          LOG.debug("No new file available in spool directory after '{}' secs, produccing empty batch",
                    poolingTimeoutSecs);
        } else {
          // file to process
          currentFile = nextAvailFile;

          // if the current offset file is null or the file returned by the spooler is greater than the current offset
          // file we take the file returned by the spooler as the new file and set the offset to zero
          // if not, it means the spooler returned us the current file, we just keep processing it from the last
          // offset we processed (known via offset tracking)
          if (file == null || nextAvailFile.getName().compareTo(file) > 0) {
            file = currentFile.getName();
            offset = 0;
          }
        }
      } catch (InterruptedException ex) {
        // the spooler was interrupted while waiting for a file, we log and return, the pipeline agent will invoke us
        // again to wait for a file again
        LOG.warn("Pooling interrupted");
      }
    }

    if (currentFile != null) {
      // we have a file to process (from before or new from spooler)
      try {
        // we ask for a batch from the currentFile starting at offset
        offset = produce(currentFile, offset, batchSize, batchMaker);
      } catch (BadSpoolFileException ex) {
        LOG.error(Errors.SPOOLDIR_01.getMessage(), ex.getFile(), ex.getPos(), ex.getMessage(), ex);
        getContext().reportError(Errors.SPOOLDIR_01, ex.getFile(), ex.getPos(), ex.getMessage());
        try {
          // then we ask the spooler to error handle the failed file
          spooler.handleCurrentFileAsError();
        } catch (IOException ex1) {
          throw new StageException(Errors.SPOOLDIR_00, currentFile, ex1.getMessage(), ex1);
        }
        // we set the offset to -1 to indicate we are done with the file and we should fetch a new one from the spooler
        offset = -1;
      }
    }
    // create a new offset using the current file and offset
    return createSourceOffset(file, offset);
  }

  /**
   * Processes a batch from the specified file and offset up to a maximum batch size. If the file is fully process
   * it must return -1, otherwise it must return the offset to continue from next invocation.
   */
  public long produce(File file, long offset, int maxBatchSize, BatchMaker batchMaker) throws StageException,
      BadSpoolFileException {
    return dataProducer.produce(file, offset, maxBatchSize, batchMaker);
  }

}
