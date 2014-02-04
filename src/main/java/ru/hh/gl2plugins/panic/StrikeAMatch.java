package ru.hh.gl2plugins.panic;

import java.util.ArrayList;

public class StrikeAMatch {
    /*
        Returns 0 < similarity < 1 of two strings
        Adapted from:
        http://www.catalysoft.com/articles/StrikeAMatch.html
     */
    public static double compareStrings(String str1, String str2) {
        char[] charStr1 = str1.toUpperCase().toCharArray();
        char[] charStr2 = str2.toUpperCase().toCharArray();
        ArrayList<Integer> pairs1 = wordLetterPairs(charStr1);
        ArrayList<Integer> pairs2 = wordLetterPairs(charStr2);
        int intersection = 0;
        int union = pairs1.size() + pairs2.size();
        for (Integer pairIndex1 : pairs1) {
            for (int j = 0; j < pairs2.size(); j++) {
                Integer pairIndex2 = pairs2.get(j);
                if (charStr1[pairIndex1] == charStr2[pairIndex2] &&
                        charStr1[pairIndex1 + 1] == charStr2[pairIndex2 + 1]) {
                    intersection++;
                    pairs2.remove(j);
                    break;
                }
            }
        }
        return (2.0 * intersection) / union;
    }

    private static ArrayList<Integer> wordLetterPairs(char[] str) {
        ArrayList<Integer> allPairs = new ArrayList<Integer>();
        for (int i = 0; i < str.length; i++) {
            if (i < str.length - 1 && !Character.isSpaceChar(str[i]) && !Character.isSpaceChar(str[i + 1])) {
                allPairs.add(i);
            }
        }
        return allPairs;
    }
}
