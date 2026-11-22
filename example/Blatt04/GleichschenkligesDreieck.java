package Blatt04;

public class GleichschenkligesDreieck extends Dreieck {
    @Override
    public double hoehe() {
        return Math.round(Math.sqrt(a * a - (c / 2) * (c / 2)));
    }

    @Override
    public double[] berechneWinkel() {
        double alpha = winkel();
        return new double[]{alpha, alpha, 180 - 2 * alpha};
    }
}
