package hex.deeplearning;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import water.*;
import water.fvec.*;
import water.parser.ParseDataset;
import water.util.Log;

import java.util.HashSet;

public class DeepLearningAutoEncoderTest extends TestUtil {
  /*
    Visualize outliers with the following R code (from smalldata/anomaly dir):

    train <- scan("ecg_discord_train.csv", sep=",")
    test  <- scan("ecg_discord_test.csv",  sep=",")
    plot.ts(train)
    plot.ts(test)
  */

  static final String PATH = "smalldata/anomaly/ecg_discord_train.csv"; //first 20 points
  static final String PATH2 = "smalldata/anomaly/ecg_discord_test.csv"; //first 22 points

  @BeforeClass() public static void setup() { stall_till_cloudsize(1); }

  @Test
  public void run() {
    long seed = 0xDECAF;

    Frame train=null, test=null;

    try {

      NFSFileVec  nfs = NFSFileVec.make(find_test_file(PATH));
      train = ParseDataset.parse(Key.make("train.hex"), nfs._key);
      NFSFileVec  nfs2 = NFSFileVec.make(find_test_file(PATH2));
      test = ParseDataset.parse(Key.make("test.hex"), nfs2._key);

      for (float sparsity_beta : new float[]{0, 0.1f}) {
        DeepLearningModel.DeepLearningParameters p = new DeepLearningModel.DeepLearningParameters();
        p._train = train._key;
        p._autoencoder = true;
        p._response_column = train.names()[train.names().length-1];
        p._convert_to_enum = false;
        p._seed = seed;
        p._hidden = new int[]{100, 100};
        p._adaptive_rate = true;
        p._train_samples_per_iteration = -1;
        p._sparsity_beta = sparsity_beta;
        p._average_activation = -0.7;
        p._l1 = 1e-4;
//    p._l2 = 1e-4;
//    p._rate = 1e-5;
        p._activation = DeepLearningModel.DeepLearningParameters.Activation.Tanh;
        p._loss = DeepLearningModel.DeepLearningParameters.Loss.MeanSquare;
//        p._initial_weight_distribution = DeepLearningModel.DeepLearningParameters.InitialWeightDistribution.Normal;
//    p._initial_weight_scale = 1e-3;
        p._epochs = 500;
//    p._shuffle_training_data = true;
        p._force_load_balance = false;
        DeepLearning dl = new DeepLearning(p);
        DeepLearningModel mymodel = null;
        try {
          mymodel = dl.trainModel().get();
        } catch (Throwable t) {
          t.printStackTrace();
          throw new RuntimeException(t);
        } finally {
          dl.remove();
        }

        Frame l2_frame_train=null, l2_frame_test=null;

        // Verification of results
        StringBuilder sb = new StringBuilder();
        try {

          sb.append("Verifying results.\n");

          // Training data

          // Reconstruct data using the same helper functions and verify that self-reported MSE agrees
          double quantile = 0.95;
          l2_frame_train = mymodel.scoreAutoEncoder(train);
          final Vec l2_train = l2_frame_train.anyVec();
          sb.append("Mean reconstruction error: ").append(l2_train.mean()).append("\n");
          Assert.assertEquals(mymodel.mse(), l2_train.mean(), 1e-7);
          Assert.assertTrue("too big a reconstruction error: " + l2_train.mean(), l2_train.mean() < 0.06);

          // manually compute L2
          Frame reconstr = mymodel.score(train); //this creates real values in original space
          double mean_l2 = 0;
          for (int r = 0; r < reconstr.numRows(); ++r) {
            double my_l2 = 0;
            for (int c = 0; c < reconstr.numCols(); ++c) {
              my_l2 += Math.pow((reconstr.vec(c).at(r) - train.vec(c).at(r)) * mymodel.model_info().data_info()._normMul[c], 2); //undo normalization here
            }
            my_l2 /= reconstr.numCols();
            mean_l2 += my_l2;
          }
          mean_l2 /= reconstr.numRows();
          reconstr.delete();
          sb.append("Mean reconstruction error (train): ").append(l2_train.mean()).append("\n");
          Assert.assertEquals(mymodel.mse(), mean_l2, 1e-7);

          // print stats and potential outliers
          sb.append("The following training points are reconstructed with an error above the ").append(quantile * 100).append("-th percentile - check for \"goodness\" of training data.\n");
          double thresh_train = mymodel.calcOutlierThreshold(l2_train, quantile);
          for (long i = 0; i < l2_train.length(); i++) {
            if (l2_train.at(i) > thresh_train) {
              sb.append(String.format("row %d : l2_train error = %5f\n", i, l2_train.at(i)));
            }
          }

          // Test data

          // Reconstruct data using the same helper functions and verify that self-reported MSE agrees
          l2_frame_test = mymodel.scoreAutoEncoder(test);
          final Vec l2_test = l2_frame_test.anyVec();
          double mult = 10;
          double thresh_test = mult * thresh_train;
          sb.append("\nFinding outliers.\n");
          sb.append("Mean reconstruction error (test): ").append(l2_test.mean()).append("\n");

          // print stats and potential outliers
          sb.append("The following test points are reconstructed with an error greater than ").append(mult).append(" times the mean reconstruction error of the training data:\n");
          HashSet<Long> outliers = new HashSet<>();
          for (long i = 0; i < l2_test.length(); i++) {
            if (l2_test.at(i) > thresh_test) {
              outliers.add(i);
              sb.append(String.format("row %d : l2 error = %5f\n", i, l2_test.at(i)));
            }
          }

          // check that the all outliers are found (and nothing else)
          Assert.assertTrue(outliers.contains(new Long(20)));
          Assert.assertTrue(outliers.contains(new Long(21)));
          Assert.assertTrue(outliers.contains(new Long(22)));
          Assert.assertTrue(outliers.size() == 3);
        } finally {
          Log.info(sb);
          // cleanup
          if (mymodel!=null) mymodel.delete();
          if (l2_frame_train!=null) l2_frame_train.delete();
          if (l2_frame_test!=null) l2_frame_test.delete();
        }
      }
    } finally {
      if (train!=null) train.delete();
      if (test!=null) test.delete();
    }
  }
}

