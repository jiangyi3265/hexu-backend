package com.ruoyi.hexu;
import java.math.BigInteger;
import java.util.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Pure, integer-only allocation using the immutable chain and price snapshot. */
public final class SettlementCalculator {
    public static final class Node {
        public final long agentId,memberId; public final int rank;
        public Node(long agentId,long memberId,int rank){this.agentId=agentId;this.memberId=memberId;this.rank=rank;}
    }
    public static final class Entry {
        public final long agentId,memberId,amount; public final String type;
        Entry(Node n,String type,long amount){this.agentId=n.agentId;this.memberId=n.memberId;this.type=type;this.amount=amount;}
    }
    public static long proportion(long amount,long numerator,long denominator){
        require(denominator>0&&numerator>=0,"比例参数错误");
        long sign=amount<0?-1:1;
        return BigInteger.valueOf(amount).abs().multiply(BigInteger.valueOf(numerator)).add(BigInteger.valueOf(denominator/2)).divide(BigInteger.valueOf(denominator)).longValueExact()*sign;
    }
    public static List<Entry> calculate(long paid,int qty,long[] prices,int[] bps,List<Node> chain,Node owner,boolean retail){
        require(qty>0&&prices.length==4&&bps.length==4,"结算参数不完整");
        require(prices[1]>=prices[2]&&prices[2]>=prices[3]&&prices[3]>=0,"职级价必须依次不高于下级价格");
        require(!chain.isEmpty()&&chain.size()<=100,"收益链为空或超过安全上限");
        List<Entry> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(Node n:chain){require(n.rank>=1&&n.rank<=3&&seen.add(n.agentId),"代理关系环或无效职级");}
        Node child=chain.get(0);
        if(retail){long margin=Math.subtractExact(paid,Math.multiplyExact(prices[child.rank],qty));require(margin>=0,"优惠后金额不足以承担代理采购成本");result.add(new Entry(child,"RETAIL",margin));}
        else {long promotionCost=Math.subtractExact(paid,Math.multiplyExact(prices[child.rank],qty));if(promotionCost!=0)result.add(new Entry(owner,"PROMOTION_COST",promotionCost));}
        for(int i=1;i<chain.size();i++){
            Node parent=chain.get(i);require(parent.rank>=child.rank,"上级职级低于下级，需先处理代理关系");
            if(parent.rank==child.rank){long peer=proportion(Math.multiplyExact(prices[child.rank],qty),bps[child.rank],10000);result.add(new Entry(parent,"PEER",peer));result.add(new Entry(owner,"PEER_COST",-peer));}
            else result.add(new Entry(parent,"LEVEL",Math.multiplyExact(prices[child.rank]-prices[parent.rank],qty)));
            child=parent;
        }
        long ownerNet=0;for(Entry e:result)if(e.agentId==owner.agentId)ownerNet+=e.amount;
        require(ownerNet>=0,"商城拥有者毛利不足以承担同级分红，请调整营销或价格");
        return result;
    }
}
