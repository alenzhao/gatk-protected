package org.broadinstitute.sting.atk;

import org.broadinstitute.sting.atk.LocusIterator;
import org.broadinstitute.sting.utils.ReferenceOrderedDatum;

import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: mdepristo
 * Date: Feb 22, 2009
 * Time: 2:52:28 PM
 * To change this template use File | Settings | File Templates.
 */
public interface LocusWalker<MapType, ReduceType> {
    void initialize();
    public String walkerType();

    // Do we actually want to operate on the context?
    boolean filter(List<ReferenceOrderedDatum> rodData, char ref, LocusContext context);

    // Map over the org.broadinstitute.sting.atk.LocusContext
    MapType map(List<ReferenceOrderedDatum> rodData, char ref, LocusContext context);

    // Given result of map function
    ReduceType reduceInit();
    ReduceType reduce(MapType value, ReduceType sum);

    void onTraversalDone();
}
