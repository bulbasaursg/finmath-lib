/*
 * (c) Copyright Christian P. Fries, Germany. All rights reserved. Contact: email@christian-fries.de.
 *
 * Created on 20.05.2005
 */
package net.finmath.marketdata.model.volatilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import net.finmath.functions.AnalyticFormulas;
import net.finmath.marketdata.model.AnalyticModelInterface;
import net.finmath.marketdata.model.curves.Curve;
import net.finmath.marketdata.model.curves.CurveInterface;
import net.finmath.marketdata.model.curves.DiscountCurveInterface;
import net.finmath.marketdata.model.curves.ForwardCurveInterface;
import net.finmath.time.TimeDiscretization;
import net.finmath.time.TimeDiscretizationInterface;

/**
 * A very simple container for Caplet volatilities.
 * 
 * It performs piecewise constant interpolation (discretization) in maturity dimension on iso-moneyness lines
 * and uses the default interpolation from the Curve class in strike dimension.
 * 
 * It allows to convert from several quoting conventions.
 * 
 * It needs a forward curve and a discount curve. The tenor length of the Caplet is inferred
 * from the forward curve.
 * 
 * @author Christian Fries
 */
public class CapletVolatilities extends AbstractVolatilitySurface {

	private ForwardCurveInterface		forwardCurve;
	private DiscountCurveInterface		discountCurve;
	private Map<Double, CurveInterface>	capletVolatilities = new HashMap<Double, CurveInterface>();
	private QuotingConvention			quotingConvention;

	/**
	 * @param name The name of this volatility surface.
	 * @param referenceDate The reference date for this volatility surface, i.e., the date which defined t=0.
	 * @param forwardCurve The underlying forward curve.
	 * @param maturities The vector of maturities of the quotes.
	 * @param strikes The vector of strikes of the quotes.
	 * @param volatilities The vector of volatilities of the quotes.
	 * @param volatilityConvention The quoting convention of the volatilities provided.
	 * @param discountCurve The associated discount curve.
	 */
	public CapletVolatilities(String name, Calendar referenceDate, ForwardCurveInterface forwardCurve,
			double[] maturities,
			double[] strikes,
			double[] volatilities,
			QuotingConvention volatilityConvention,
			DiscountCurveInterface discountCurve)  {
		super(name, referenceDate);
		this.forwardCurve = forwardCurve;
		this.discountCurve = discountCurve;
		this.quotingConvention = volatilityConvention;
		
		if(maturities.length != strikes.length || maturities.length != volatilities.length)
			throw new IllegalArgumentException("Length of vectors is not equal.");
		
		for(int i=0; i<volatilities.length; i++) {
			double maturity		= maturities[i];
			double strike		= strikes[i];
			double volatility	= volatilities[i];
			add(maturity, strike, volatility);
		}
	}

	/**
	 * Private constructor for empty surface, to add points to it.
	 * 
	 * @param name The name of this volatility surface.
	 * @param referenceDate The reference date for this volatility surface, i.e., the date which defined t=0.
	 */
	private CapletVolatilities(String name, Calendar referenceDate) {
		super(name, referenceDate);
	}

	/**
	 * @param maturity
	 * @param strike
	 * @param volatility
	 */
	private void add(double maturity, double strike, double volatility) {
		CurveInterface curve = capletVolatilities.get(maturity);
		try {
			if(curve == null) curve = (new Curve.CurveBuilder()).addPoint(strike, volatility, true).build();
			else curve = curve.getCloneBuilder().addPoint(strike, volatility, true).build();
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException("Unable to build curve.");
		}
		capletVolatilities.put(maturity, curve);
	}

	@Override
	public double getValue(double maturity, double strike, VolatilitySurfaceInterface.QuotingConvention quotingConvention) {
		return getValue(null, maturity, strike, quotingConvention);
	}

	@Override
	public double getValue(AnalyticModelInterface model, double maturity, double strike, VolatilitySurfaceInterface.QuotingConvention quotingConvention) {
		if(maturity == 0) return 0;
		TimeDiscretizationInterface maturities = new TimeDiscretization(capletVolatilities.keySet().toArray(new Double[0]));
		
		//		double maturityLowerOrEqual		= maturities.getTime(maturities.getTimeIndexNearestLessOrEqual(maturity));
		double maturityGreaterOfEqual	= maturities.getTime(Math.min(maturities.getTimeIndexNearestGreaterOrEqual(maturity),maturities.getNumberOfTimes()-1));

		// Interpolation / extrapolation is performed on iso-moneyness lines.
		double adjustedStrike	= forwardCurve.getValue(maturityGreaterOfEqual) + (strike - forwardCurve.getValue(maturity));
		double value			= capletVolatilities.get(maturityGreaterOfEqual).getValue(adjustedStrike);

		return convertFromTo(maturity, strike, value, this.quotingConvention, quotingConvention);
	}

	@Override
	public QuotingConvention getQuotingConvention() {
		return quotingConvention;
	}

	/**
	 * Convert the value of a caplet from on quoting convention to another quoting convention.
	 * 
	 * @param optionMaturity Option maturity of the caplet.
	 * @param optionStrike Option strike of the cpalet.
	 * @param value Value of the caplet given in the form of <code>fromQuotingConvention</code>.
	 * @param fromQuotingConvention The quoting convention of the given value.
	 * @param toQuotingConvention The quoting convention requested.
	 * @return Value of the caplet given in the form of <code>toQuotingConvention</code>. 
	 */
	public double convertFromTo(double optionMaturity, double optionStrike, double value, QuotingConvention fromQuotingConvention, QuotingConvention toQuotingConvention) {

		if(fromQuotingConvention.equals(toQuotingConvention)) return value;
		
		double forward = forwardCurve.getForward(null, optionMaturity);
		double payoffUnit = discountCurve.getDiscountFactor(optionMaturity+forwardCurve.getPaymentOffset(optionMaturity)) * forwardCurve.getPaymentOffset(optionMaturity);
		
		if(toQuotingConvention.equals(QuotingConvention.PRICE) && fromQuotingConvention.equals(QuotingConvention.VOLATILITYLOGNORMAL)) {
			return AnalyticFormulas.blackScholesGeneralizedOptionValue(forward, value, optionMaturity, optionStrike, payoffUnit);
		}
		else if(toQuotingConvention.equals(QuotingConvention.PRICE) && fromQuotingConvention.equals(QuotingConvention.VOLATILITYNORMAL)) {
			return AnalyticFormulas.bachelierOptionValue(forward, value, optionMaturity, optionStrike, payoffUnit);
		}
		else if(toQuotingConvention.equals(QuotingConvention.VOLATILITYLOGNORMAL) && fromQuotingConvention.equals(QuotingConvention.PRICE)) {
			return AnalyticFormulas.blackScholesOptionImpliedVolatility(forward, optionMaturity, optionStrike, payoffUnit, value);
		}
		else if(toQuotingConvention.equals(QuotingConvention.VOLATILITYNORMAL) && fromQuotingConvention.equals(QuotingConvention.PRICE)) {
			return AnalyticFormulas.bachelierOptionImpliedVolatility(forward, optionMaturity, optionStrike, payoffUnit, value);
		}
		else {
			return convertFromTo(optionMaturity, optionStrike, convertFromTo(optionMaturity, optionStrike, value, fromQuotingConvention, QuotingConvention.PRICE), QuotingConvention.PRICE, toQuotingConvention);
		}
	}

	public static CapletVolatilities fromFile(File inputFile) throws FileNotFoundException {
		// Read data
		BufferedReader		dataStream	= new BufferedReader(new InputStreamReader(new FileInputStream(inputFile)));		
		ArrayList<String>	datasets	= new ArrayList<String>();
		try {
			while(true) {
				String line = dataStream.readLine();
	
				// Check for end of file
				if(line == null)	break;
	
				// Ignore non caplet data
				if(!line.startsWith("caplet\t")) continue;

				datasets.add(line);
			}
			dataStream.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		// @TODO: Name and reference date have to be set?!
		CapletVolatilities capletVolatilities = new CapletVolatilities(null, null);
		
		// Parse data
		for(int datasetIndex=0; datasetIndex<datasets.size(); datasetIndex++) {
			StringTokenizer stringTokenizer = new StringTokenizer(datasets.get(datasetIndex),"\t");
	
			try {
				// Skip identifier
				stringTokenizer.nextToken();
				double maturity			= Double.parseDouble(stringTokenizer.nextToken());
				double strike			= Double.parseDouble(stringTokenizer.nextToken());
				double capletVolatility	= Double.parseDouble(stringTokenizer.nextToken());
				capletVolatilities.add(maturity, strike, capletVolatility);
			}
			catch(Exception e) {
				e.printStackTrace();
			}
		}
		
		return capletVolatilities;
	}
}
