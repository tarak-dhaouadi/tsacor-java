# tsacor-java — copyright and licensing

tsacor-java is distributed under the GNU General Public License, version 2 or
(at your option) any later version (GPL-2.0-or-later); see [LICENSE.md](LICENSE.md). It is a
Java port of the R package tsacor, which contains code ported from the R
package RTSA (GPL (>= 2)); the licence therefore carries over.

## Copyright holders

- Tarak Dhaouadi -- tsacor (the R package: interface, statistics layer for
  correlations on Fisher's z scale, tests, documentation) and this Java port.
- Anne Lyngholm Soerensen, Markus Harboe Olsen, Theis Lange and Christian
  Gluud -- the R package RTSA 0.2.2 (GPL (>= 2)), the algorithms and code the
  boundary engine is derived from.

## Files derived from RTSA (through tsacor and tsahr)

- `src/main/java/org/tsacor/RtsaCore.java`   ← `src/rtsa_core.h` of tsacor
  (RTSA's recursive-integration engine: init_int(), recur_int(), prob(),
  z_n_w(), searchfunc(), alpha_boundary(), beta_boundary(), esOF(), sd_inf()).
- `src/main/java/org/tsacor/RtsaEngine.java` ← `R/rtsa_engine.R` of tsacor
  (RTSA's boundaries() design and analysis routes, information-scale root
  searches, rm_bs handling, look-spacing diagnostics).
- The boundary-orchestration section of `src/main/java/org/tsacor/TsaCor.java`
  ← the corresponding section of R/tsa_cor.R of tsacor / tsa_hr() of tsahr.
- The reference numbers in `src/test/java/org/tsacor/SelfTest.java` taken from
  `inst/extdata/rtsa_0.2.2_reference.R` (numbers printed by RTSA 0.2.2).

## Not derived from other software

The distribution functions, Brent root finder (a transcription of the
zeroin algorithm as used by R's uniroot), random-effects estimators, data
readers and the Swing / Java2D user interface were written for this project
and use only the Java standard library. The original TSA program of the
Copenhagen Trial Unit (TSA.jar) was NOT used as a source of code.

## Origin of RTSA / TSA

RTSA is the R version of Trial Sequential Analysis (TSA), originally developed
as a stand-alone Java program by the Copenhagen Trial Unit
(<https://ctu.dk/tools>):

> Copenhagen Trial Unit, Centre for Clinical Intervention Research,  
> Department 3344, Rigshospitalet, DK-2100 Copenhagen O, Denmark.  
> Tel. +45 3545 7171, Fax +45 3545 7101, E-mail: tsa@ctu.dk

If you use tsacor-java for boundary computations, please also cite RTSA and
the TSA software, and Wetterslev et al. (2009), BMC Med Res Methodol 9:86.

The bundled example data set (`src/main/resources/org/tsacor/r_meta.xlsx`) is
the one shipped with the R package tsacor (`inst/extdata/r_meta.xlsx`).
