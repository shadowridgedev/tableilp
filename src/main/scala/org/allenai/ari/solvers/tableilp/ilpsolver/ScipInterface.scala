package org.allenai.ari.solvers.tableilp.ilpsolver

import org.allenai.common.Logging

import de.zib.jscip.nativ.jni._

/** This is a generic interface to the SCIP ILP solver providing a number of common initialization
  * steps and access to the SCIP environment.
  */
class ScipInterface(val probName: String) extends Logging {
  /** config: local log file where SCIP output is stored for debugging purposes */
  private val ScipLogFile = "scip.log"

  // initialization: load JNI library
  logger.debug("Java library path = " + System.getProperty("java.library.path"))
  JniScipLibraryLoader.loadLibrary()

  // initialization: create various handlers in the SCIP environment
  // create the SCIP environment
  private val env: JniScip = new JniScip

  // create the SCIP variable environment
  private val envVar: JniScipVar = new JniScipVar

  // create SCIP set packing constraint environment
  private lazy val envConsSetppc = new JniScipConsSetppc

  // create the SCIP linear constraint environment
  private lazy val envConsLinear = new JniScipConsLinear

  // initialization: create a SCIP instance, an array of variables
  private val scip: Long = env.create

  // initialization: set various parameters
  env.printVersion(scip, 0) // print SCIP version to stdout
  env.setMessagehdlrQuiet(scip, false) // set message handler of SCIP to quiet or not
  env.setMessagehdlrLogfile(scip, ScipLogFile) // write all SCIP output to the log file
  env.includeDefaultPlugins(scip) // include default plugins of SCIP

  // initialization: create empty problem tied to the given problem name
  env.createProbBasic(scip, probName)

  /** set objection function as minimization */
  def setAsMinimization() {
    env.setObjsense(scip, JniScipObjsense.SCIP_OBJSENSE_MINIMIZE)
  }

  /** set objection function as maximization */
  def setAsMaximization() {
    env.setObjsense(scip, JniScipObjsense.SCIP_OBJSENSE_MAXIMIZE)
  }

  /** create a binary variable */
  def createBinaryVar(name: String, obj: Double): Long = {
    env.createVarBasic(scip, name, 0, 1, obj, JniScipVartype.SCIP_VARTYPE_BINARY)
  }

  /** create an integer variable */
  def createIntegerVar(name: String, lb: Double, ub: Double, objCoeff: Double): Long = {
    env.createVarBasic(scip, name, lb, ub, objCoeff, JniScipVartype.SCIP_VARTYPE_INTEGER)
  }

  /** create a continuous variable */
  def createContinuousVar(name: String, lb: Double, ub: Double, objCoeff: Double): Long = {
    env.createVarBasic(scip, name, lb, ub, objCoeff, JniScipVartype.SCIP_VARTYPE_CONTINUOUS)
  }

  /** add variable to the environment */
  def addVar(x: Long): Unit = env.addVar(scip, x)

  /** add constraint to the environment */
  def addCons(c: Long): Unit = env.addCons(scip, c)

  /** release constraint from the environment */
  def releaseCons(c: Long): Unit = env.releaseCons(scip, c)

  /** get the name of a variable */
  def varGetName(l: Long): String = envVar.varGetName(l)

  /** get pointer to the best solution found */
  def getBestSol: Long = env.getBestSol(scip)

  /** get objective value (primal bound) */
  def getPrimalbound: Double = env.getPrimalbound(scip)

  /** get solution status */
  def getStatus: Int = env.getStatus(scip)

  /** get solution values */
  def getSolVals(vars: Array[Long]): Array[Double] = {
    env.getSolVals(scip, getBestSol, vars.length, vars)
  }

  /** get one solution value */
  def getSolVal(variable: Long): Double = {
    env.getSolVal(scip, getBestSol, variable)
  }

  /** Sets the lower bound for a variable */
  def chgVarLb(x: Long, newBound: Double): Unit = env.chgVarLb(scip, x, newBound)

  /** Sets the upper bound for a variable */
  def chgVarUb(x: Long, newBound: Double): Unit = env.chgVarUb(scip, x, newBound)

  /** Creates and captures a linear constraint in its most basic version; all constraint flags are
    * set to their basic value as explained for the method SCIPcreateConsLinear(); all flags can
    * be set via SCIPsetConsFLAGNAME methods in scip.h
    *
    * @see SCIPcreateConsLinear() for information about the basic constraint flag configuration
    *
    * @param name                  name of constraint
    * @param vars                  array with variables of constraint entries
    * @param coeffs                array with coefficients of constraint entries
    * @param lhs                   left hand side of constraint
    * @param rhs                   right hand side of constraint
    */
  def createConsBasicLinear(name: String, vars: Seq[Long], coeffs: Seq[Double],
    lhs: Double, rhs: Double): Long = {
    envConsLinear.createConsBasicLinear(scip, name, vars.length, vars.toArray, coeffs.toArray,
      lhs, rhs)
  }

  /** Calls createConsBasicLinear and adds the constraint to the solver */
  def addConsBasicLinear(name: String, vars: Seq[Long], coeffs: Seq[Double],
    lhs: Double, rhs: Double): Unit = {
    val cons = createConsBasicLinear(name, vars, coeffs, lhs, rhs)
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Adds coefficient to a linear constraint (if it is not zero)
    *
    * @param cons                  constraint data
    * @param x                     variable of constraint entry
    * @param coeff                 coefficient of constraint entry
    */
  def addCoefLinear(cons: Long, x: Long, coeff: Double): Unit = {
    envConsLinear.addCoefLinear(scip, cons, x, coeff)
  }

  /** Gets the array of coefficient values in the linear constraint; the user must not modify
    * this array!
    *
    * @param cons                  constraint data
    */
  def getValsLinear(cons: Long): Array[Double] = {
    envConsLinear.getValsLinear(scip, cons)
  }

  /** Creates and captures a basic Set Partitioning constraint, \sum_i x_i = 1, emulating C++ API's
    * createConsBasicSetpack constraint which is not provided in the Java API.
    *
    * @param name                  name of constraint
    * @param vars                  array with variables of constraint entries
    */
  def createConsBasicSetpart(name: String, vars: Seq[Long]): Long = {
    envConsSetppc.createConsSetpart(scip, name, vars.length, vars.toArray,
      true, true, true, true, true, false, false, false, false, false)
  }

  /** Calls createConsBasicSetpart and adds the constraint to the solver */
  def addConsBasicSetpart(name: String, vars: Seq[Long]): Unit = {
    val cons = createConsBasicSetpart(name, vars)
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Creates and captures a basic Set Packing constraint, \sum_i x_i <= 1, emulating C++ API's
    * createConsBasicSetpack constraint which is not provided in the Java API.
    *
    * @param name                  name of constraint
    * @param vars                  array with variables of constraint entries
    */
  def createConsBasicSetpack(name: String, vars: Seq[Long]): Long = {
    envConsSetppc.createConsSetpack(scip, name, vars.length, vars.toArray,
      true, true, true, true, true, false, false, false, false, false)
  }

  /** Calls createConsBasicSetpack and adds the constraint to the solver */
  def addConsBasicSetpack(name: String, vars: Seq[Long]): Unit = {
    val cons = createConsBasicSetpack(name, vars)
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Creates and captures a basic Set covering constraint, \sum_i x_i >= 1, emulating C++ API's
    * createConsBasicSetpack constraint which is not provided in the Java API.
    *
    * @param name                  name of constraint
    * @param vars                  array with variables of constraint entries
    */
  def createConsBasicSetcover(name: String, vars: Seq[Long]): Long = {
    envConsSetppc.createConsSetcover(scip, name, vars.length, vars.toArray,
      true, true, true, true, true, false, false, false, false, false)
  }

  /** Calls createConsBasicSetcover and adds the constraint to the solver */
  def addConsBasicSetcover(name: String, vars: Seq[Long]): Unit = {
    val cons = createConsBasicSetcover(name, vars)
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Adds coefficient in set partitioning / packing / covering constraint
    *
    * @param cons                  constraint data
    * @param x                     variable to add to the constraint
    */
  def addCoefSetppc(cons: Long, x: Long): Unit = {
    envConsSetppc.addCoefSetppc(scip, cons, x)
  }

  /** Adds the constraint x <= y + c */
  def addConsXLeqYPlusC(name: String, x: Long, y: Long, c: Double): Unit = {
    val cons = createConsBasicLinear(name, Seq(x, y), Seq(1d, -1d), -1000d, c)
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Adds the constraint x <= y */
  def addConsXLeqY(name: String, x: Long, y: Long): Unit = {
    addConsXLeqYPlusC(name, x, y, 0d)
  }

  /** Adds the constraint x = y + c */
  def addConsXEqYPlusC(name: String, x: Long, y: Long, c: Double): Unit = {
    val cons = createConsBasicLinear(name, Seq(x, y), Seq(1d, -1d), c, c)
    env.addCons(scip, cons)
    env.releaseCons(scip, cons)
  }

  /** Adds the constraint x = y */
  def addConsXEqY(name: String, x: Long, y: Long): Unit = {
    addConsXEqYPlusC(name, x, y, 0d)
  }

  /** Solve the ILP model and report the result */
  def solve(): Unit = {
    env.solve(scip)
    logger.info(s"Solution status: $getStatus")
    logger.info(s"Objective value: $getPrimalbound")
  }

  /** Print result of the call to solve(), along with solution values of vars */
  def printResult(vars: Array[Long]): Unit = {
    // retrieve best solution found so far
    if (getStatus == JniScipStatus.SCIP_STATUS_OPTIMAL) {
      val values = getSolVals(vars)
      val solution = vars.zip(values) map { case (x, v) => varGetName(x) + " : " + v }
      logger.info("Solution found:\n\t" + solution.mkString("\n\t"))
    }
  }
}