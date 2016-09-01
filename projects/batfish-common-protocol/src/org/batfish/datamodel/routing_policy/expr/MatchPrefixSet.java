package org.batfish.datamodel.routing_policy.expr;

import org.batfish.datamodel.Route;
import org.batfish.datamodel.routing_policy.Environment;
import org.batfish.datamodel.routing_policy.Result;

import com.fasterxml.jackson.annotation.JsonCreator;

public class MatchPrefixSet extends AbstractBooleanExpr {

   /**
    *
    */
   private static final long serialVersionUID = 1L;

   private PrefixSetExpr _expr;

   @JsonCreator
   public MatchPrefixSet() {
   }

   public MatchPrefixSet(PrefixSetExpr expr) {
      _expr = expr;
   }

   @Override
   public Result evaluate(Environment environment, Route route) {
      boolean match = _expr.matches(environment, route);
      Result result = new Result();
      result.setBooleanValue(match);
      return result;
   }

   public PrefixSetExpr getExpr() {
      return _expr;
   }

   public void setExpr(PrefixSetExpr expr) {
      _expr = expr;
   }

}