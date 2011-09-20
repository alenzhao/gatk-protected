/*
 * Copyright (c) 2011, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.codecs.vcf;

import org.broad.tribble.Tribble;
import org.broad.tribble.TribbleException;
import org.broad.tribble.index.DynamicIndexCreator;
import org.broad.tribble.index.Index;
import org.broad.tribble.index.IndexFactory;
import org.broad.tribble.util.LittleEndianOutputStream;
import org.broad.tribble.util.PositionalStream;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;

import java.io.*;

/**
 * this class writes VCF files
 */
public abstract class IndexingVCFWriter implements VCFWriter {
    final private String name;

    private File indexFile = null;
    private OutputStream outputStream;
    private PositionalStream positionalStream = null;
    private DynamicIndexCreator indexer = null;
    private LittleEndianOutputStream idxStream = null;

    protected IndexingVCFWriter(String name, File location, OutputStream output, boolean enableOnTheFlyIndexing) {
        outputStream = output;
        this.name = name;

        if ( enableOnTheFlyIndexing ) {
            indexFile = Tribble.indexFile(location);
            try {
                idxStream = new LittleEndianOutputStream(new FileOutputStream(indexFile));
                //System.out.println("Creating index on the fly for " + location);
                indexer = new DynamicIndexCreator(IndexFactory.IndexBalanceApproach.FOR_SEEK_TIME);
                indexer.initialize(location, indexer.defaultBinSize());
                positionalStream = new PositionalStream(output);
                outputStream = positionalStream;
            } catch ( IOException ex ) {
                // No matter what we keep going, since we don't care if we can't create the index file
                idxStream = null;
                indexer = null;
                positionalStream = null;
                indexFile = null;
            }
        }
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public String getStreamName() {
        return name;
    }

    public abstract void writeHeader(VCFHeader header);

    /**
     * attempt to close the VCF file
     */
    public void close() {
        // try to close the index stream (keep it separate to help debugging efforts)
        if ( indexer != null ) {
            try {
                Index index = indexer.finalizeIndex(positionalStream.getPosition());
                index.write(idxStream);
                idxStream.close();
            } catch (IOException e) {
                throw new ReviewedStingException("Unable to close index for " + getStreamName(), e);
            }
        }
    }

    /**
     * add a record to the file
     *
     * @param vc      the Variant Context object
     */
    public void add(VariantContext vc) {
        // if we are doing on the fly indexing, add the record ***before*** we write any bytes
        if ( indexer != null )
            indexer.addFeature(vc, positionalStream.getPosition());
    }

    protected static final String writerName(File location, OutputStream stream) {
        return location == null ? stream.toString() : location.getAbsolutePath();
    }

    protected static OutputStream openOutputStream(File location) {
        try {
            return new FileOutputStream(location);
        } catch (FileNotFoundException e) {
            throw new ReviewedStingException("Unable to create VCF file at location: " + location, e);
        }
    }
}
