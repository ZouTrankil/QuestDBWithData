import com.zoutrankil.data.factor.engine.FactorSeries;
import java.util.Arrays;
public class ConstantWindowProbe { public static void main(String[] a){
 double[] input=new double[80];for(int i=0;i<30;i++)input[i]=10000*Math.sin(i*.7);Arrays.fill(input,30,80,0);
 double[] mean=FactorSeries.rolling(input,20,10,false),std=FactorSeries.rolling(input,20,10,true);
 for(int i=49;i<80;i++)if(mean[i]!=0||std[i]!=0)throw new AssertionError("zero window");
 Arrays.fill(input,30,80,12.34);input[40]=Double.NaN;
 mean=FactorSeries.rolling(input,20,10,false);std=FactorSeries.rolling(input,20,10,true);
 for(int i=49;i<80;i++)if(mean[i]!=12.34||std[i]!=0)throw new AssertionError("constant with null");
 System.out.println("PASS: variable history -> constant/zero window, including missing rows, exact mean and zero variance");
}}
