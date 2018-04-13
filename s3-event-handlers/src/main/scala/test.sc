object Solution {
  def twoSum(nums: Array[Int], target: Int): Array[Int] = {
    nums.map { i =>
      if(i < target) {
        val rem = target - i
        if(nums.contains(rem)) rem
        else -1
      } else -1
    }.filter(_ > 0)
  }
}

Solution.twoSum(Array(2, 7, 11, 15), 9)