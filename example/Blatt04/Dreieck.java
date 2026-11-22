package Blatt04;

public abstract class Dreieck implements Flaecheninhalt {
    public double a;
    public double b;
    public double c;

    @Override
    public abstract double hoehe();

    public abstract double[] berechneWinkel();

    @Override
    public double flaeche() {
        return Math.round(c * hoehe() / 2);
    }

    public double winkel() {
        return Math.round(Math.acos((a * a - b * b - c * c) / (-2 * c * b)) * 180 / pi);
    }

    public double umfang() {
        return Math.round(a + b + c);
    }
}
