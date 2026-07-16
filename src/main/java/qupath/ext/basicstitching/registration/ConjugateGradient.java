package qupath.ext.basicstitching.registration;

/**
 * Conjugate gradient solver for a symmetric positive-definite system, without ever building the
 * matrix.
 *
 * <p>The caller supplies {@code A} as an {@link Operator} -- a function that multiplies a vector.
 * For the global position solve that operator is a loop over the edge list, so the "matrix" costs
 * O(edges) to apply and O(1) to store. Materializing it would be an n-by-n array: 800 MB for a
 * 10,000-tile grid whose edge list fits in a few hundred kilobytes.
 *
 * <p>The iterate starts at zero. That matters beyond convenience: a row that the operator only ever
 * touches through its diagonal (an isolated tile) keeps a zero residual and a zero search
 * direction, so it stays at exactly zero for every iteration rather than drifting in on rounding
 * error.
 */
final class ConjugateGradient {

    private ConjugateGradient() {}

    /**
     * A symmetric positive-definite linear operator.
     *
     * <p>Implementations must fully overwrite {@code out}; it is a scratch buffer reused across
     * iterations and its previous contents are meaningless.
     */
    @FunctionalInterface
    interface Operator {
        /**
         * @param x the vector to multiply; must not be modified
         * @param out receives {@code A * x}
         */
        void apply(double[] x, double[] out);
    }

    /**
     * Solves {@code A x = b}.
     *
     * @param n the system size
     * @param a the operator, which must be symmetric positive-definite
     * @param b the right-hand side, of length {@code n}
     * @param tol convergence tolerance on the residual, relative to {@code ||b||}
     * @param maxIter iteration cap; convergence normally comes first
     * @return the solution vector, or a zero vector when {@code n} is zero or {@code b} is
     */
    static double[] solve(int n, Operator a, double[] b, double tol, int maxIter) {
        double[] x = new double[n];
        if (n == 0) {
            return x;
        }
        double bNorm = Math.sqrt(dot(b, b));
        if (bNorm == 0) {
            // A * 0 = 0 = b. Exactly solved, and returning early keeps the all-nominal case free.
            return x;
        }
        double[] r = b.clone();
        double[] p = r.clone();
        double[] ap = new double[n];
        double rsOld = dot(r, r);
        double target = tol * bNorm;
        for (int iter = 0; iter < maxIter; iter++) {
            a.apply(p, ap);
            double pAp = dot(p, ap);
            if (pAp <= 0) {
                // Only reachable if the operator is not positive definite. Stop with the best
                // iterate rather than dividing by a non-positive curvature.
                break;
            }
            double alpha = rsOld / pAp;
            for (int k = 0; k < n; k++) {
                x[k] += alpha * p[k];
                r[k] -= alpha * ap[k];
            }
            double rsNew = dot(r, r);
            if (Math.sqrt(rsNew) <= target) {
                break;
            }
            double beta = rsNew / rsOld;
            for (int k = 0; k < n; k++) {
                p[k] = r[k] + beta * p[k];
            }
            rsOld = rsNew;
        }
        return x;
    }

    private static double dot(double[] u, double[] v) {
        double sum = 0;
        for (int k = 0; k < u.length; k++) {
            sum += u[k] * v[k];
        }
        return sum;
    }
}
