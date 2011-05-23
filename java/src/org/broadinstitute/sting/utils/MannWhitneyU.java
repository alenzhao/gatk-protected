package org.broadinstitute.sting.utils;

import cern.jet.math.Arithmetic;
import cern.jet.random.Normal;
import com.google.java.contract.Ensures;
import com.google.java.contract.Requires;
import org.apache.commons.math.MathException;
import org.apache.commons.math.distribution.NormalDistribution;
import org.apache.commons.math.distribution.NormalDistributionImpl;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.StingException;

import java.util.Comparator;
import java.util.TreeSet;

/**
 * Created by IntelliJ IDEA.
 * User: chartl
 */
public class MannWhitneyU {

    private static Normal STANDARD_NORMAL = new Normal(0.0,1.0,null);
    private static NormalDistribution APACHE_NORMAL = new NormalDistributionImpl(0.0,1.0,1e-2);

    private TreeSet<Pair<Number,USet>> observations;
    private int sizeSet1;
    private int sizeSet2;

    public MannWhitneyU() {
        observations = new TreeSet<Pair<Number,USet>>(new DitheringComparator());
        sizeSet1 = 0;
        sizeSet2 = 0;
    }

    /**
     * Add an observation into the observation tree
     * @param n: the observation (a number)
     * @param set: whether the observation comes from set 1 or set 2
     */
    public void add(Number n, USet set) {
        observations.add(new Pair<Number,USet>(n,set));
        if ( set == USet.SET1 ) {
            ++sizeSet1;
        } else {
            ++sizeSet2;
        }
    }

    public Pair<Long,Long> getR1R2() {
        long u1 = calculateOneSidedU(observations,MannWhitneyU.USet.SET1);
        long n1 = sizeSet1*(sizeSet1+1)/2;
        long r1 = u1 + n1;
        long n2 = sizeSet2*(sizeSet2+1)/2;
        long u2 = n1*n2-u1;
        long r2 = u2 + n2;

        return new Pair<Long,Long>(r1,r2);
    }

    /**
     * Runs the one-sided test under the hypothesis that the data in set "lessThanOther" stochastically
     * dominates the other set
     * @param lessThanOther - either Set1 or Set2
     * @return - u-based z-approximation, and p-value associated with the test (p-value is exact for small n,m)
     */
    @Requires({"validateObservations(observations)", "lessThanOther != null"})
    @Ensures({"result != null", "! Double.isInfinite(result.getFirst())", "! Double.isInfinite(result.getSecond())"})
    public Pair<Double,Double> runOneSidedTest(USet lessThanOther) {
        long u = calculateOneSidedU(observations, lessThanOther);
        int n = lessThanOther == USet.SET1 ? sizeSet1 : sizeSet2;
        int m = lessThanOther == USet.SET1 ? sizeSet2 : sizeSet1;
        if ( n == 0 || m == 0 ) {
            // test is uninformative as one or both sets have no observations
            return new Pair<Double,Double>(Double.NaN,Double.NaN);
        }

        return calculateP(n, m, u, false);
    }

    /**
     * Runs the standard two-sided test,
     * returns the u-based z-approximate and p values.
     * @return a pair holding the u and p-value.
     */
    @Ensures({"result != null", "! Double.isInfinite(result.getFirst())", "! Double.isInfinite(result.getSecond())"})
    @Requires({"validateObservations(observations)"})
    public Pair<Double,Double> runTwoSidedTest() {
        Pair<Long,USet> uPair = calculateTwoSidedU(observations);
        long u = uPair.first;
        int n = uPair.second == USet.SET1 ? sizeSet1 : sizeSet2;
        int m = uPair.second == USet.SET1 ? sizeSet2 : sizeSet1;
        if ( n == 0 || m == 0 ) {
            // test is uninformative as one or both sets have no observations
            return new Pair<Double,Double>(Double.NaN,Double.NaN);
        }
        return calculateP(n, m, u, true);
    }

    /**
     * Given a u statistic, calculate the p-value associated with it, dispatching to approximations where appropriate
     * @param n - The number of entries in the stochastically smaller (dominant) set
     * @param m - The number of entries in the stochastically larger (dominated) set
     * @param u - the Mann-Whitney U value
     * @param twoSided - is the test twosided
     * @return the (possibly approximate) p-value associated with the MWU test, and the (possibly approximate) z-value associated with it
     * todo -- there must be an approximation for small m and large n
     */
    @Requires({"m > 0","n > 0"})
    @Ensures({"result != null", "! Double.isInfinite(result.getFirst())", "! Double.isInfinite(result.getSecond())"})
    public static Pair<Double,Double> calculateP(int n, int m, long u, boolean twoSided) {
        Pair<Double,Double> zandP;
        if ( n > 8 && m > 8 ) {
            // large m and n - normal approx
            zandP = calculatePNormalApproximation(n,m,u, twoSided);
        } else if ( n > 5 && m > 7 ) {
            // large m, small n - sum uniform approx
            // todo -- find the appropriate regimes where this approximation is actually better enough to merit slowness
            // pval = calculatePUniformApproximation(n,m,u);
            zandP = calculatePNormalApproximation(n, m, u, twoSided);
        } else if ( n > 8 || m > 8 ) {
            zandP = calculatePFromTable(n, m, u, twoSided);
        } else {
            // small m and n - full approx
            zandP = calculatePRecursively(n,m,u, twoSided);
        }

        return zandP;
    }

    public static Pair<Double,Double> calculatePFromTable(int n, int m, long u, boolean twoSided) {
        // todo -- actually use a table for:
        // todo      - n large, m small
        return calculatePNormalApproximation(n,m,u, twoSided);
    }

    /**
     * Uses a normal approximation to the U statistic in order to return a cdf p-value. See Mann, Whitney [1947]
     * @param n - The number of entries in the stochastically smaller (dominant) set
     * @param m - The number of entries in the stochastically larger (dominated) set
     * @param u - the Mann-Whitney U value
     * @param twoSided - whether the test should be two sided
     * @return p-value associated with the normal approximation
     */
    @Requires({"m > 0","n > 0"})
    @Ensures({"result != null", "! Double.isInfinite(result.getFirst())", "! Double.isInfinite(result.getSecond())"})
    public static Pair<Double,Double> calculatePNormalApproximation(int n,int m,long u, boolean twoSided) {
        double z = getZApprox(n,m,u);
        if ( twoSided ) {
            return new Pair<Double,Double>(z,2.0*(z < 0 ? STANDARD_NORMAL.cdf(z) : 1.0-STANDARD_NORMAL.cdf(z)));
        } else {
            return new Pair<Double,Double>(z,STANDARD_NORMAL.cdf(z));
        }
    }

    /**
     * Calculates the Z-score approximation of the u-statistic
     * @param n - The number of entries in the stochastically smaller (dominant) set
     * @param m - The number of entries in the stochastically larger (dominated) set
     * @param u - the Mann-Whitney U value
     * @return the asymptotic z-approximation corresponding to the MWU p-value for n < m
     */
    @Requires({"m > 0","n > 0"})
    @Ensures({"result != null", "! Double.isInfinite(result)"})
    private static double getZApprox(int n, int m, long u) {
        double mean = ( ((long)m)*n+1.0)/2;
        double var = (((long) n)*m*(n+m+1.0))/12;
        double z = ( u - mean )/Math.sqrt(var);
        return z;
    }

    /**
     * Uses a sum-of-uniform-0-1 random variable approximation to the U statistic in order to return an approximate
     * p-value. See Buckle, Kraft, van Eeden [1969] (approx) and Billingsly [1995] or Stephens, MA [1966, biometrika] (sum of uniform CDF)
     * @param n - The number of entries in the stochastically smaller (dominant) set
     * @param m - The number of entries in the stochastically larger (dominated) set
     * @param u - mann-whitney u value
     * @return p-value according to sum of uniform approx
     * todo -- this is currently not called due to not having a good characterization of where it is significantly more accurate than the
     * todo -- normal approxmation (e.g. enough to merit the runtime hit)
     */
    public static double calculatePUniformApproximation(int n, int m, long u) {
        long R = u + (n*(n+1))/2;
        double a = Math.sqrt(m*(n+m+1));
        double b = (n/2.0)*(1-Math.sqrt((n+m+1)/m));
        double z = b + ((double)R)/a;
        if ( z < 0 ) { return 1.0; }
        else if ( z > n ) { return 0.0; }
        else {
            if ( z > ((double) n) /2 ) {
                return 1.0-1/((double)Arithmetic.factorial(n))*uniformSumHelper(z, (int) Math.floor(z), n, 0);
            } else {
                return 1/((double)Arithmetic.factorial(n))*uniformSumHelper(z, (int) Math.floor(z), n, 0);
            }
        }
    }

    /**
     * Helper function for the sum of n uniform random variables
     * @param z - value at which to compute the (un-normalized) cdf
     * @param m - a cutoff integer (defined by m <= z < m + 1)
     * @param n - the number of uniform random variables
     * @param k - holder variable for the recursion (alternatively, the index of the term in the sequence)
     * @return the (un-normalized) cdf for the sum of n random variables
     */
    private static double uniformSumHelper(double z, int m, int n, int k) {
        if ( k > m ) { return 0; }
        int coef = (k % 2 == 0) ? 1 : -1;
        return coef*Arithmetic.binomial(n,k)*Math.pow(z-k,n) + uniformSumHelper(z,m,n,k+1);
    }

    /**
     * Calculates the U-statistic associated with a two-sided test (e.g. the RV from which one set is drawn
     * stochastically dominates the RV from which the other set is drawn); two-sidedness is accounted for
     * later on simply by multiplying the p-value by 2.
     *
     * Recall: If X stochastically dominates Y, the test is for occurrences of Y before X, so the lower value of u is chosen
     * @param observed - the observed data
     * @return the minimum of the U counts (set1 dominates 2, set 2 dominates 1)
     */
    @Requires({"observed != null", "observed.size() > 0", "validateObservations(observed)"})
    @Ensures({"result != null","result.first > 0"})
    public static Pair<Long,USet> calculateTwoSidedU(TreeSet<Pair<Number,USet>> observed) {
        int set1SeenSoFar = 0;
        int set2SeenSoFar = 0;
        long uSet1DomSet2 = 0;
        long uSet2DomSet1 = 0;
        USet previous = null;
        for ( Pair<Number,USet> dataPoint : observed ) {

            if ( dataPoint.second == USet.SET1 ) {
                ++set1SeenSoFar;
            } else {
                ++set2SeenSoFar;
            }

            if ( previous != null ) {
                if ( dataPoint.second == USet.SET1 ) {
                    uSet2DomSet1 += set2SeenSoFar;
                } else {
                    uSet1DomSet2 += set1SeenSoFar;
                }
            }

            previous = dataPoint.second;
        }

        return uSet1DomSet2 < uSet2DomSet1 ? new Pair<Long,USet>(uSet1DomSet2,USet.SET1) : new Pair<Long,USet>(uSet2DomSet1,USet.SET2);
    }

    /**
     * Calculates the U-statistic associated with the one-sided hypothesis that "dominator" stochastically dominates
     * the other U-set. Note that if S1 dominates S2, we want to count the occurrences of points in S2 coming before points in S1.
     * @param observed - the observed data points, tagged by each set
     * @param dominator - the set that is hypothesized to be stochastically dominating
     * @return the u-statistic associated with the hypothesis
     */
    @Requires({"observed != null","dominator != null","observed.size() > 0","validateObservations(observed)"})
    @Ensures({"return > 0"})
    public static long calculateOneSidedU(TreeSet<Pair<Number,USet>> observed,USet dominator) {
        long otherBeforeDominator = 0l;
        int otherSeenSoFar = 0;
        for ( Pair<Number,USet> dataPoint : observed ) {
            if ( dataPoint.second != dominator ) {
                ++otherSeenSoFar;
            } else {
                otherBeforeDominator += otherSeenSoFar;
            }
        }

        return otherBeforeDominator;
    }

    /**
     * The Mann-Whitney U statistic follows a recursive equation (that enumerates the proportion of possible
     * binary strings of "n" zeros, and "m" ones, where a one precedes a zero "u" times). This accessor
     * calls into that recursive calculation.
     * @param n: number of set-one entries (hypothesis: set one is stochastically less than set two)
     * @param m: number of set-two entries
     * @param u: number of set-two entries that precede set-one entries (e.g. 0,1,0,1,0 -> 3 )
     * @param twoSided: whether the test is two sided or not. The recursive formula is symmetric, multiply by two for two-sidedness.
     * @return the probability under the hypothesis that all sequences are equally likely of finding a set-two entry preceding a set-one entry "u" times.
     */
    @Requires({"m > 0","n > 0","u > 0"})
    @Ensures({"result != null","! Double.isInfinite(result.getFirst())", "! Double.isInfinite(result.getSecond())"})
    public static Pair<Double,Double> calculatePRecursively(int n, int m, long u, boolean twoSided) {
        if ( m > 8 && n > 5 ) { throw new StingException(String.format("Please use the appropriate (normal or sum of uniform) approximation. Values n: %d, m: %d",n,m)); }
        double p = cpr(n,m,u);
        double z;
        try {
            z = APACHE_NORMAL.inverseCumulativeProbability(p);
        } catch (MathException me) {
            throw new StingException("A math exception occurred in inverting the probability",me);
        }

        return new Pair<Double,Double>(z,(twoSided ? 2.0*p : p));
    }

    /**
     * Hook into CPR with sufficient warning (for testing purposes)
     * calls into that recursive calculation.
     * @param n: number of set-one entries (hypothesis: set one is stochastically less than set two)
     * @param m: number of set-two entries
     * @param u: number of set-two entries that precede set-one entries (e.g. 0,1,0,1,0 -> 3 )
     * @return same as cpr
     * @deprecated - for testing only (really)
     */
    @Deprecated
    public static double calculatePRecursivelyDoNotCheckValuesEvenThoughItIsSlow(int n, int m, long u) {
        return cpr(n,m,u);
    }

    /**
     * : just a shorter name for calculatePRecursively. See Mann, Whitney, [1947]
     * @param n: number of set-1 entries
     * @param m: number of set-2 entries
     * @param u: number of times a set-2 entry as preceded a set-1 entry
     * @return recursive p-value
     */
    private static double cpr(int n, int m, long u) {
        if ( u < 0 || n == 0 && m == 0 ) {
            return 0.0;
        }
        if ( m == 0 || n == 0 ) {
            // there are entries in set 1 or set 2, so no set-2 entry can precede a set-1 entry; thus u must be zero.
            // note that this exists only for edification, as when we reach this point, the coefficient on this term is zero anyway
            return ( u == 0 ) ? 1.0 : 0.0;
        }


        return (((double)n)/(n+m))*cpr(n-1,m,u-m) + (((double)m)/(n+m))*cpr(n,m-1,u);
    }

    /**
     * hook into the data tree, for testing purposes only
     * @return  observations
     * @deprecated - only for testing
     */
    @Deprecated
    public TreeSet<Pair<Number,USet>> getObservations() {
        return observations;
    }

    /**
     * hook into the set sizes, for testing purposes only
     * @return size set 1, size set 2
     * @deprecated - only for testing
     */
    public Pair<Integer,Integer> getSetSizes() {
        return new Pair<Integer,Integer>(sizeSet1,sizeSet2);
    }

    protected static boolean validateObservations(TreeSet<Pair<Number,USet>> tree) {
        boolean seen1 = false;
        boolean seen2 = false;
        boolean seenInvalid = false;
        for ( Pair<Number,USet> p : tree) {
            if ( ! seen1 && p.getSecond() == USet.SET1 ) {
                seen1 = true;
            }

            if ( ! seen2 && p.getSecond() == USet.SET2 ) {
                seen2 = true;
            }

            if ( Double.isNaN(p.getFirst().doubleValue()) || Double.isInfinite(p.getFirst().doubleValue())) {
                seenInvalid = true;
            }

            return ! seenInvalid && seen1 && seen2;
        }
    }

    /**
     * A comparator class which uses dithering on tie-breaking to ensure that the internal treeset drops no values
     * and to ensure that rank ties are broken at random.
     */
    private class DitheringComparator implements Comparator<Pair<Number,USet>> {

        public DitheringComparator() {}

        public boolean equals(Object other) { return false; }

        public int compare(Pair<Number,USet> left, Pair<Number,USet> right) {
            double comp = Double.compare(left.first.doubleValue(),right.first.doubleValue());
            if ( comp > 0 ) { return 1; }
            if ( comp < 0 ) { return -1; }
            return GenomeAnalysisEngine.getRandomGenerator().nextBoolean() ? -1 : 1;
        }
    }

    public enum USet { SET1, SET2 }

}
