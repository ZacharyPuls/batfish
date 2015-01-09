package batfish.representation.cisco;

import batfish.representation.Prefix;

public class DynamicBgpPeerGroup extends BgpPeerGroup {

   /**
    *
    */
   private static final long serialVersionUID = 1L;
   private Prefix _prefix;

   public DynamicBgpPeerGroup(Prefix prefix) {
      _prefix = prefix;
   }

   public String getName() {
      return _prefix.toString();
   }

   public Prefix getPrefix() {
      return _prefix;
   }

}
