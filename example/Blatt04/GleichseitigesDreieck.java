package Blatt04;

public class GleichseitigesDreieck extends Dreieck {
    @Override
    public double hoehe() {
        return Math.round((a / 2) * Math.sqrt(3.0));
    }

    @Override
    public double[] berechneWinkel() {
        return new double[]{60.0, 60.0, 60.0};
    }
}
